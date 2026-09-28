package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/jackc/pgx/v5/pgxpool"

	"stars-outdoor/server/api"
)

// memTeams is teamStore in memory.
type memTeams struct {
	mu        sync.Mutex
	teams     []*memTeam
	seq       int64 // last stored position or message
	positions []memPosition
	messages  []memMessage
	images    map[string]*memImage
}

type memTeam struct {
	code      string
	initiator int64
	ended     bool
	members   []api.Member // positions unused
}

type memPosition struct {
	seq, team, user int64
	p               api.Position
}

func (m *memTeams) leaveOthersLocked(user int64, keep int64) {
	for i, t := range m.teams {
		if id := int64(i + 1); id != keep && !t.ended && slices.ContainsFunc(t.members, func(mb api.Member) bool { return mb.Id == user }) {
			m.leaveLocked(id, user)
		}
	}
}

func (m *memTeams) leaveLocked(id, user int64) {
	t := m.teams[id-1]
	t.members = slices.DeleteFunc(t.members, func(mb api.Member) bool { return mb.Id == user })
	m.positions = slices.DeleteFunc(m.positions, func(p memPosition) bool { return p.team == id && p.user == user })
	if len(t.members) == 0 {
		t.ended = true
	}
}

func (m *memTeams) activeLocked(code string) int64 {
	for i, t := range m.teams {
		if t.code == code && !t.ended {
			return int64(i + 1)
		}
	}
	return 0
}

func (m *memTeams) create(_ context.Context, code string, user int64, name string) (int64, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.activeLocked(code) != 0 {
		return 0, false, nil
	}
	m.leaveOthersLocked(user, 0)
	m.teams = append(m.teams, &memTeam{code: code, initiator: user, members: []api.Member{{Id: user, Name: name, Sharing: true}}})
	return int64(len(m.teams)), true, nil
}

func (m *memTeams) join(_ context.Context, code string, user int64, name string) (int64, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id := m.activeLocked(code)
	if id == 0 {
		return 0, false, nil
	}
	m.leaveOthersLocked(user, id)
	t := m.teams[id-1]
	if !slices.ContainsFunc(t.members, func(mb api.Member) bool { return mb.Id == user }) {
		t.members = append(t.members, api.Member{Id: user, Name: name, Sharing: true})
	}
	return id, true, nil
}

func (m *memTeams) team(_ context.Context, id, after int64) (api.Team, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if id < 1 || id > int64(len(m.teams)) {
		return api.Team{}, false, nil
	}
	t := m.teams[id-1]
	res := api.Team{Id: id, Code: t.code, Initiator: t.initiator, Ended: t.ended, Cursor: m.seq, Members: []api.Member{}, Messages: []api.Message{}}
	for _, mb := range t.members {
		mb.Positions = []api.Position{}
		for _, p := range m.positions {
			if p.seq > after && p.team == id && p.user == mb.Id {
				mb.Positions = append(mb.Positions, p.p)
			}
		}
		res.Members = append(res.Members, mb)
	}
	for _, msg := range m.messages {
		if msg.team == id && msg.m.Seq > after {
			res.Messages = append(res.Messages, msg.m)
		}
	}
	return res, true, nil
}

func (m *memTeams) leave(_ context.Context, id, user int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.leaveLocked(id, user)
	return nil
}

func (m *memTeams) end(_ context.Context, id int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.teams[id-1]
	t.ended = true
	for i := range t.members {
		t.members[i].Sharing = false
	}
	return nil
}

func (m *memTeams) setSharing(_ context.Context, id, user int64, sharing bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i, mb := range m.teams[id-1].members {
		if mb.Id == user {
			m.teams[id-1].members[i].Sharing = sharing
		}
	}
	return nil
}

func (m *memTeams) addPositions(_ context.Context, id, user int64, ps []api.Position) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, p := range ps {
		m.seq++
		m.positions = append(m.positions, memPosition{m.seq, id, user, p})
	}
	return nil
}

// teamHandler serves the team routes from memory, or from an emptied PostgreSQL at TEST_DATABASE_URL
// (e.g. deploy/'s PostGIS) to check the SQL too.
func teamHandler(t *testing.T) http.Handler {
	h, _ := teamServer(t)
	return h
}

// teamServer is teamHandler and the teams behind it.
func teamServer(t *testing.T) (http.Handler, *teams) {
	sms := &aliyunSMS{endpoint: (&fakeAliyun{}).serve(t).URL, keyID: "ID", secret: "S", signName: "x", template: "1", client: http.DefaultClient}
	var users userStore = &memUsers{sessions: map[string]int64{}}
	var store teamStore = &memTeams{}
	if url := os.Getenv("TEST_DATABASE_URL"); url != "" {
		db, err := pgxpool.New(context.Background(), url)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(db.Close)
		if _, err := db.Exec(context.Background(), "DROP TABLE IF EXISTS team_messages, team_images, team_positions, team_members, teams, sessions, users;"+usersSchema+teamsSchema); err != nil {
			t.Fatal(err)
		}
		users, store = pgUsers{db}, pgTeams{db}
	}
	tm := newTeams(store, t.TempDir())
	return withMiddleware(routes(1, okDB, nil, nil, nil, nil, newAccounts(sms, users), tm), 1, 1000), tm
}

func do(h http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func teamOf(t *testing.T, w *httptest.ResponseRecorder) api.Team {
	t.Helper()
	var tm api.Team
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &tm) != nil {
		t.Fatalf("team: %d %s", w.Code, w.Body)
	}
	return tm
}

func member(tm api.Team, id int64) *api.Member {
	for i := range tm.Members {
		if tm.Members[i].Id == id {
			return &tm.Members[i]
		}
	}
	return nil
}

func path(tm api.Team, rest string) string { return "/v1/teams/" + strconv.FormatInt(tm.Id, 10) + rest }

func TestCreateAndJoinByCode(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	if len(tm.Code) != 4 || tm.Initiator != 1 || tm.Me != 1 || tm.Ended || len(tm.Members) != 1 || tm.Members[0].Name != "尾号8000" || !tm.Members[0].Sharing {
		t.Fatalf("created: %+v", tm)
	}
	joined := teamOf(t, do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`","name":"老王"}`))
	if joined.Id != tm.Id || joined.Me != 2 || len(joined.Members) != 2 || member(joined, 2).Name != "老王" {
		t.Fatalf("joined: %+v", joined)
	}
	// Joining again is harmless.
	if again := teamOf(t, do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)); len(again.Members) != 2 {
		t.Fatalf("again: %+v", again)
	}
	if got := teamOf(t, do(h, "GET", path(tm, ""), a, "")); len(got.Members) != 2 {
		t.Fatalf("get: %+v", got)
	}
	other := strconv.Itoa((mustAtoi(tm.Code) + 1) % 10000)
	for len(other) < 4 {
		other = "0" + other
	}
	if w := do(h, "POST", "/v1/teams/join", b, `{"code":"`+other+`"}`); w.Code != 404 || !strings.Contains(w.Body.String(), "team_not_found") {
		t.Fatalf("wrong code: %d %s", w.Code, w.Body)
	}
	for _, body := range []string{`{"code":"12"}`, `{"code":"abcd"}`, `{"code":"1234","name":"` + strings.Repeat("名", 21) + `"}`} {
		if w := do(h, "POST", "/v1/teams/join", b, body); w.Code != 400 {
			t.Fatalf("%s: %d %s", body, w.Code, w.Body)
		}
	}
	if w := do(h, "GET", path(tm, ""), "nope", ""); w.Code != 401 {
		t.Fatalf("no login: %d", w.Code)
	}
	c := login(t, h, "13700137000")
	if w := do(h, "GET", path(tm, ""), c, ""); w.Code != 404 {
		t.Fatalf("stranger: %d", w.Code)
	}
}

func mustAtoi(s string) int { n, _ := strconv.Atoi(s); return n }

func TestPositionsAfterCursor(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	if w := do(h, "POST", path(tm, "/positions"), b, `{"positions":[{"time":100,"lat":34,"lon":108,"battery":80}]}`); w.Code != 204 {
		t.Fatalf("post: %d %s", w.Code, w.Body)
	}
	first := teamOf(t, do(h, "GET", path(tm, ""), a, ""))
	if ps := member(first, 2).Positions; len(ps) != 1 || ps[0].Lat != 34 || *ps[0].Battery != 80 {
		t.Fatalf("positions: %+v", ps)
	}
	do(h, "POST", path(tm, "/positions"), b, `{"positions":[{"time":130,"lat":34.001,"lon":108}]}`)
	next := teamOf(t, do(h, "GET", path(tm, "?after="+strconv.FormatInt(first.Cursor, 10)), a, ""))
	if ps := member(next, 2).Positions; len(ps) != 1 || ps[0].Time != 130 {
		t.Fatalf("after cursor: %+v", ps)
	}
	for _, body := range []string{`{"positions":[]}`, `{"positions":[{"time":1,"lat":91,"lon":0}]}`, `{"positions":[{"time":1,"lat":0,"lon":0,"battery":101}]}`} {
		if w := do(h, "POST", path(tm, "/positions"), b, body); w.Code != 400 {
			t.Fatalf("%s: %d", body, w.Code)
		}
	}
}

func TestStopSharingDropsPositions(t *testing.T) {
	h := teamHandler(t)
	a := login(t, h, "13800138000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	if w := do(h, "PUT", path(tm, "/sharing"), a, `{"sharing":false}`); w.Code != 204 {
		t.Fatalf("stop: %d %s", w.Code, w.Body)
	}
	do(h, "POST", path(tm, "/positions"), a, `{"positions":[{"time":100,"lat":34,"lon":108}]}`)
	got := teamOf(t, do(h, "GET", path(tm, ""), a, ""))
	if me := member(got, 1); me.Sharing || len(me.Positions) != 0 {
		t.Fatalf("not sharing: %+v", me)
	}
}

func TestLeaveAndLastOneOutEnds(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	do(h, "POST", path(tm, "/positions"), b, `{"positions":[{"time":100,"lat":34,"lon":108}]}`)
	if w := do(h, "POST", path(tm, "/leave"), b, ""); w.Code != 204 {
		t.Fatalf("leave: %d", w.Code)
	}
	if w := do(h, "GET", path(tm, ""), b, ""); w.Code != 404 {
		t.Fatalf("left member: %d", w.Code)
	}
	if got := teamOf(t, do(h, "GET", path(tm, ""), a, "")); len(got.Members) != 1 {
		t.Fatalf("after leave: %+v", got)
	}
	do(h, "POST", path(tm, "/leave"), a, "")
	if w := do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`); w.Code != 404 {
		t.Fatalf("empty team still joinable: %d", w.Code)
	}
}

func TestCreatingAnotherTeamLeavesTheFirst(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	first := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+first.Code+`"}`)
	teamOf(t, do(h, "POST", "/v1/teams", b, `{}`))
	if got := teamOf(t, do(h, "GET", path(first, ""), a, "")); len(got.Members) != 1 {
		t.Fatalf("b still in first: %+v", got)
	}
}

func TestEndTripInitiatorOnly(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	if w := do(h, "POST", path(tm, "/end"), b, ""); w.Code != 403 || !strings.Contains(w.Body.String(), "not_initiator") {
		t.Fatalf("member ends: %d %s", w.Code, w.Body)
	}
	if w := do(h, "POST", path(tm, "/end"), a, ""); w.Code != 204 {
		t.Fatalf("end: %d", w.Code)
	}
	got := teamOf(t, do(h, "GET", path(tm, ""), b, ""))
	if !got.Ended || got.Members[0].Sharing || got.Members[1].Sharing {
		t.Fatalf("ended: %+v", got)
	}
	if w := do(h, "POST", path(tm, "/positions"), b, `{"positions":[{"time":1,"lat":0,"lon":0}]}`); w.Code != 409 || !strings.Contains(w.Body.String(), "team_ended") {
		t.Fatalf("position after end: %d %s", w.Code, w.Body)
	}
	if w := do(h, "PUT", path(tm, "/sharing"), b, `{"sharing":true}`); w.Code != 409 {
		t.Fatalf("share after end: %d", w.Code)
	}
	if w := do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`); w.Code != 404 {
		t.Fatalf("ended team joinable: %d", w.Code)
	}
}

func dial(t *testing.T, srv *httptest.Server, p, token string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, res, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+p, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err != nil {
		code := 0
		if res != nil {
			code = res.StatusCode
		}
		t.Fatalf("dial: %d %v", code, err)
	}
	t.Cleanup(func() { c.CloseNow() })
	return c
}

func next(t *testing.T, c *websocket.Conn) api.Team {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, b, err := c.Read(ctx)
	var tm api.Team
	if err != nil || json.Unmarshal(b, &tm) != nil {
		t.Fatalf("read: %v %s", err, b)
	}
	return tm
}

func TestLiveSendsSnapshotThenChanges(t *testing.T) {
	h := teamHandler(t)
	srv := httptest.NewServer(h)
	defer srv.Close()
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", path(tm, "/positions"), a, `{"positions":[{"time":90,"lat":34,"lon":108}]}`)
	c := dial(t, srv, path(tm, "/live"), a)
	if snap := next(t, c); len(member(snap, 1).Positions) != 1 {
		t.Fatalf("snapshot: %+v", snap)
	}
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	if ev := next(t, c); ev.Me != 1 || len(ev.Members) != 2 || len(member(ev, 1).Positions) != 0 {
		t.Fatalf("join event: %+v", ev)
	}
	do(h, "POST", path(tm, "/positions"), b, `{"positions":[{"time":100,"lat":34.1,"lon":108}]}`)
	ev := next(t, c)
	if ps := member(ev, 2).Positions; len(ps) != 1 || ps[0].Lat != 34.1 {
		t.Fatalf("position event: %+v", ev)
	}
	// Reconnecting with the cursor gets only what came after it.
	c2 := dial(t, srv, path(tm, "/live?after="+strconv.FormatInt(ev.Cursor, 10)), b)
	if snap := next(t, c2); len(member(snap, 1).Positions)+len(member(snap, 2).Positions) != 0 {
		t.Fatalf("resumed snapshot: %+v", snap)
	}
	do(h, "POST", path(tm, "/end"), a, "")
	if ev := next(t, c); !ev.Ended {
		t.Fatalf("end event: %+v", ev)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, _, err := c.Read(ctx); websocket.CloseStatus(err) != websocket.StatusNormalClosure {
		t.Fatalf("not closed after end: %v", err)
	}
	// Not a member: no socket.
	cl := login(t, h, "13700137000")
	r, _ := http.NewRequest("GET", srv.URL+path(tm, "/live"), nil)
	r.Header.Set("Authorization", "Bearer "+cl)
	if res, err := http.DefaultClient.Do(r); err != nil || res.StatusCode != 404 {
		t.Fatalf("stranger live: %v %v", res, err)
	}
}
