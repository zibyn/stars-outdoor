package main

// 队伍 (spec §2.11): made by a 发起人, joined by a 4-digit code. Members report positions over REST; the
// team's changes go out over a WebSocket, broadcast in memory (one server instance, §3.2). Teams, members
// and positions live in PostgreSQL, so a restart mid-trip loses nothing but the open sockets.

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/big"
	"net/http"
	"regexp"
	"slices"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"stars-outdoor/server/api"
)

const teamsSchema = `
CREATE TABLE IF NOT EXISTS teams (
	id bigserial PRIMARY KEY,
	code text NOT NULL,
	initiator bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	created_at timestamptz NOT NULL DEFAULT now(),
	ended_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS teams_active_code ON teams (code) WHERE ended_at IS NULL;
CREATE TABLE IF NOT EXISTS team_members (
	team_id bigint NOT NULL REFERENCES teams ON DELETE CASCADE,
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	name text NOT NULL,
	sharing boolean NOT NULL DEFAULT true,
	joined_at timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (team_id, user_id)
);
CREATE INDEX IF NOT EXISTS team_members_user ON team_members (user_id);
CREATE TABLE IF NOT EXISTS team_positions (
	seq bigserial PRIMARY KEY,
	team_id bigint NOT NULL,
	user_id bigint NOT NULL,
	time bigint NOT NULL,
	lat double precision NOT NULL,
	lon double precision NOT NULL,
	battery int,
	FOREIGN KEY (team_id, user_id) REFERENCES team_members ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS team_positions_team ON team_positions (team_id, seq);`

// teamStore keeps teams; pgTeams in production, in memory in tests.
// ponytail: a team left on the way by create or join (the caller was in another) isn't broadcast; its
// sockets hear of it on their next reconnect. That only happens once the app lost track of its team.
type teamStore interface {
	// create makes a team with code, user its 发起人 and first member, leaving any other active team;
	// ok is false if an active team has the code already.
	create(ctx context.Context, code string, user int64, name string) (id int64, ok bool, err error)
	// join adds user to the active team with code (as it is if already in), leaving any other active team.
	join(ctx context.Context, code string, user int64, name string) (id int64, ok bool, err error)
	// team is the team with each member's positions stored after cursor after.
	team(ctx context.Context, id, after int64) (api.Team, bool, error)
	// leave drops the member and their positions; the last one out ends the team.
	leave(ctx context.Context, id, user int64) error
	// end ends the trip: the code is free again and nobody shares.
	end(ctx context.Context, id int64) error
	setSharing(ctx context.Context, id, user int64, sharing bool) error
	addPositions(ctx context.Context, id, user int64, ps []api.Position) error
}

type pgTeams struct{ db *pgxpool.Pool }

// leaveOthers is leave for every active team of user's but keep, in tx.
func leaveOthers(ctx context.Context, tx pgx.Tx, user, keep int64) error {
	_, err := tx.Exec(ctx, `WITH gone AS (
		DELETE FROM team_members m USING teams t
		WHERE m.team_id = t.id AND m.user_id = $1 AND t.id <> $2 AND t.ended_at IS NULL RETURNING t.id)
		UPDATE teams SET ended_at = now() WHERE id IN (SELECT id FROM gone)
		AND NOT EXISTS (SELECT 1 FROM team_members WHERE team_id = teams.id AND user_id <> $1)`, user, keep)
	return err
}

func (p pgTeams) create(ctx context.Context, code string, user int64, name string) (id int64, ok bool, err error) {
	err = pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if err := leaveOthers(ctx, tx, user, 0); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, "INSERT INTO teams (code, initiator) VALUES ($1, $2) RETURNING id", code, user).Scan(&id); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "INSERT INTO team_members (team_id, user_id, name) VALUES ($1, $2, $3)", id, user, name)
		return err
	})
	if pe := (*pgconn.PgError)(nil); errors.As(err, &pe) && pe.Code == "23505" { // the code is taken
		return 0, false, nil
	}
	return id, err == nil, err
}

func (p pgTeams) join(ctx context.Context, code string, user int64, name string) (id int64, ok bool, err error) {
	err = pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		err := tx.QueryRow(ctx, "SELECT id FROM teams WHERE code = $1 AND ended_at IS NULL FOR UPDATE", code).Scan(&id)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if err := leaveOthers(ctx, tx, user, id); err != nil {
			return err
		}
		ok = true
		_, err = tx.Exec(ctx, "INSERT INTO team_members (team_id, user_id, name) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING", id, user, name)
		return err
	})
	return id, ok && err == nil, err
}

func (p pgTeams) team(ctx context.Context, id, after int64) (t api.Team, ok bool, err error) {
	err = p.db.QueryRow(ctx, "SELECT id, code, initiator, ended_at IS NOT NULL, (SELECT coalesce(max(seq), 0) FROM team_positions) FROM teams WHERE id = $1", id).
		Scan(&t.Id, &t.Code, &t.Initiator, &t.Ended, &t.Cursor)
	if errors.Is(err, pgx.ErrNoRows) {
		return t, false, nil
	}
	if err != nil {
		return t, false, err
	}
	rows, _ := p.db.Query(ctx, "SELECT user_id, name, sharing FROM team_members WHERE team_id = $1 ORDER BY joined_at", id)
	t.Members, err = pgx.CollectRows(rows, func(r pgx.CollectableRow) (m api.Member, err error) {
		m.Positions = []api.Position{}
		return m, r.Scan(&m.Id, &m.Name, &m.Sharing)
	})
	if err != nil {
		return t, false, err
	}
	rows, _ = p.db.Query(ctx, "SELECT user_id, time, lat, lon, battery FROM team_positions WHERE team_id = $1 AND seq > $2 AND seq <= $3 ORDER BY seq", id, after, t.Cursor)
	var user int64
	var pos api.Position
	_, err = pgx.ForEachRow(rows, []any{&user, &pos.Time, &pos.Lat, &pos.Lon, &pos.Battery}, func() error {
		if i := slices.IndexFunc(t.Members, func(m api.Member) bool { return m.Id == user }); i >= 0 {
			t.Members[i].Positions = append(t.Members[i].Positions, pos)
		}
		pos.Battery = nil
		return nil
	})
	return t, err == nil, err
}

func (p pgTeams) leave(ctx context.Context, id, user int64) error {
	return pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "DELETE FROM team_members WHERE team_id = $1 AND user_id = $2", id, user); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "UPDATE teams SET ended_at = now() WHERE id = $1 AND ended_at IS NULL AND NOT EXISTS (SELECT 1 FROM team_members WHERE team_id = $1)", id)
		return err
	})
}

func (p pgTeams) end(ctx context.Context, id int64) error {
	return pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "UPDATE teams SET ended_at = now() WHERE id = $1 AND ended_at IS NULL", id); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "UPDATE team_members SET sharing = false WHERE team_id = $1", id)
		return err
	})
}

func (p pgTeams) setSharing(ctx context.Context, id, user int64, sharing bool) error {
	_, err := p.db.Exec(ctx, "UPDATE team_members SET sharing = $3 WHERE team_id = $1 AND user_id = $2", id, user, sharing)
	return err
}

func (p pgTeams) addPositions(ctx context.Context, id, user int64, ps []api.Position) error {
	_, err := p.db.CopyFrom(ctx, pgx.Identifier{"team_positions"}, []string{"team_id", "user_id", "time", "lat", "lon", "battery"},
		pgx.CopyFromSlice(len(ps), func(i int) ([]any, error) {
			return []any{id, user, ps[i].Time, ps[i].Lat, ps[i].Lon, ps[i].Battery}, nil
		}))
	return err
}

// teams serves the team routes and holds the live sockets.
// ponytail: one lock around every change and its broadcast, so a socket sees changes in cursor order;
// per-team locks if many teams report at once.
type teams struct {
	store teamStore
	mu    sync.Mutex
	subs  map[int64][]*teamSub // by team id
}

type teamSub struct {
	user int64
	ch   chan api.Team // closed when the socket should end
}

func newTeams(store teamStore) *teams { return &teams{store: store, subs: map[int64][]*teamSub{}} }

// Outcomes of a change's fn: the caller isn't in the team, the trip ended, only the 发起人 may, or
// nothing to do (no broadcast).
var (
	errNotMember    = errors.New("not a member")
	errEnded        = errors.New("team ended")
	errNotInitiator = errors.New("not the initiator")
	errNoChange     = errors.New("no change")
)

// change runs fn on team id as member user sees it (no positions) and sends every socket on it what
// changed. Checks belong in fn: it runs under the lock, so nothing changes between them and the write.
// Sockets of members no longer in it, all of them once it ended, and ones too far behind get closed.
func (tm *teams) change(ctx context.Context, id, user int64, fn func(t api.Team, me api.Member) error) error {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	before, ok, err := tm.member(ctx, id, user, math.MaxInt64)
	if err != nil {
		return err
	}
	if !ok {
		return errNotMember
	}
	err = fn(before, before.Members[slices.IndexFunc(before.Members, func(m api.Member) bool { return m.Id == user })])
	if errors.Is(err, errNoChange) {
		return nil
	}
	if err != nil {
		return err
	}
	after, ok, err := tm.store.team(ctx, id, before.Cursor)
	if err != nil || !ok {
		return err
	}
	tm.subs[id] = slices.DeleteFunc(tm.subs[id], func(s *teamSub) bool {
		select {
		case s.ch <- after:
		default: // too far behind: it reconnects with its cursor
			close(s.ch)
			return true
		}
		if after.Ended || !isMember(after, s.user) {
			close(s.ch)
			return true
		}
		return false
	})
	return nil
}

func isMember(t api.Team, user int64) bool {
	return slices.ContainsFunc(t.Members, func(m api.Member) bool { return m.Id == user })
}

// member is team id as user sees it with positions after after; ok is false if user isn't in it.
func (tm *teams) member(ctx context.Context, id, user, after int64) (api.Team, bool, error) {
	t, ok, err := tm.store.team(ctx, id, after)
	t.Me = user
	return t, ok && isMember(t, user), err
}

var teamCode = regexp.MustCompile(`^[0-9]{4}$`)

var teamNotFound = api.TeamNotFoundJSONResponse{Error: api.ErrorCodeTeamNotFound}
var teamEnded = api.Error{Error: api.ErrorCodeTeamEnded}

// teamName is name, or 尾号 and the last 4 digits of the caller's number; ok is false if too long.
func teamName(ctx context.Context, name *string) (string, bool) {
	if name == nil || *name == "" {
		p := userOf(ctx).phone
		return "尾号" + p[max(0, len(p)-4):], true
	}
	return *name, utf8.RuneCountInString(*name) <= 20
}

func (s *server) PostTeam(ctx context.Context, req api.PostTeamRequestObject) (api.PostTeamResponseObject, error) {
	name, ok := teamName(ctx, req.Body.Name)
	if !ok {
		return api.PostTeam400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u := userOf(ctx).id
	// ponytail: random codes, retried on a clash; plenty while active teams are far fewer than 10000.
	for range 20 {
		n, _ := rand.Int(rand.Reader, big.NewInt(10000))
		id, ok, err := s.teams.store.create(ctx, fmt.Sprintf("%04d", n), u, name)
		if err != nil {
			return nil, err
		}
		if ok {
			t, _, err := s.teams.member(ctx, id, u, 0)
			return api.PostTeam200JSONResponse(t), err
		}
	}
	return nil, errors.New("no free team code")
}

func (s *server) PostTeamJoin(ctx context.Context, req api.PostTeamJoinRequestObject) (api.PostTeamJoinResponseObject, error) {
	name, ok := teamName(ctx, req.Body.Name)
	if !ok || !teamCode.MatchString(req.Body.Code) {
		return api.PostTeamJoin400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u := userOf(ctx).id
	id, found, err := s.teams.store.join(ctx, req.Body.Code, u, name)
	if err != nil {
		return nil, err
	}
	if !found {
		return api.PostTeamJoin404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	}
	// Joined already; a change with nothing to do just tells the sockets.
	if err := s.teams.change(ctx, id, u, func(api.Team, api.Member) error { return nil }); err != nil {
		return nil, err
	}
	t, _, err := s.teams.member(ctx, id, u, 0)
	return api.PostTeamJoin200JSONResponse(t), err
}

func (s *server) GetTeam(ctx context.Context, req api.GetTeamRequestObject) (api.GetTeamResponseObject, error) {
	t, ok, err := s.teams.member(ctx, req.Id, userOf(ctx).id, deref(req.Params.After))
	if err != nil {
		return nil, err
	}
	if !ok {
		return api.GetTeam404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	}
	return api.GetTeam200JSONResponse(t), nil
}

func deref(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

func (s *server) PostTeamPositions(ctx context.Context, req api.PostTeamPositionsRequestObject) (api.PostTeamPositionsResponseObject, error) {
	ps := req.Body.Positions
	if len(ps) == 0 || len(ps) > 1000 || slices.ContainsFunc(ps, func(p api.Position) bool {
		return math.Abs(p.Lat) > 90 || math.Abs(p.Lon) > 180 || p.Battery != nil && (*p.Battery < 0 || *p.Battery > 100)
	}) {
		return api.PostTeamPositions400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u := userOf(ctx).id
	err := s.teams.change(ctx, req.Id, u, func(t api.Team, me api.Member) error {
		switch {
		case t.Ended:
			return errEnded
		case !me.Sharing: // dropped
			return errNoChange
		}
		return s.teams.store.addPositions(ctx, req.Id, u, ps)
	})
	switch {
	case errors.Is(err, errNotMember):
		return api.PostTeamPositions404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case errors.Is(err, errEnded):
		return api.PostTeamPositions409JSONResponse(teamEnded), nil
	case err != nil:
		return nil, err
	}
	return api.PostTeamPositions204Response{}, nil
}

func (s *server) PutTeamSharing(ctx context.Context, req api.PutTeamSharingRequestObject) (api.PutTeamSharingResponseObject, error) {
	u := userOf(ctx).id
	err := s.teams.change(ctx, req.Id, u, func(t api.Team, _ api.Member) error {
		if t.Ended {
			return errEnded
		}
		return s.teams.store.setSharing(ctx, req.Id, u, req.Body.Sharing)
	})
	switch {
	case errors.Is(err, errNotMember):
		return api.PutTeamSharing404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case errors.Is(err, errEnded):
		return api.PutTeamSharing409JSONResponse(teamEnded), nil
	case err != nil:
		return nil, err
	}
	return api.PutTeamSharing204Response{}, nil
}

func (s *server) PostTeamLeave(ctx context.Context, req api.PostTeamLeaveRequestObject) (api.PostTeamLeaveResponseObject, error) {
	u := userOf(ctx).id
	err := s.teams.change(ctx, req.Id, u, func(api.Team, api.Member) error { return s.teams.store.leave(ctx, req.Id, u) })
	switch {
	case errors.Is(err, errNotMember):
		return api.PostTeamLeave404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case err != nil:
		return nil, err
	}
	return api.PostTeamLeave204Response{}, nil
}

func (s *server) PostTeamEnd(ctx context.Context, req api.PostTeamEndRequestObject) (api.PostTeamEndResponseObject, error) {
	u := userOf(ctx).id
	err := s.teams.change(ctx, req.Id, u, func(t api.Team, _ api.Member) error {
		if t.Initiator != u {
			return errNotInitiator
		}
		return s.teams.store.end(ctx, req.Id)
	})
	switch {
	case errors.Is(err, errNotMember):
		return api.PostTeamEnd404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case errors.Is(err, errNotInitiator):
		return api.PostTeamEnd403JSONResponse{Error: api.ErrorCodeNotInitiator}, nil
	case err != nil:
		return nil, err
	}
	return api.PostTeamEnd204Response{}, nil
}

func (s *server) GetTeamLive(ctx context.Context, req api.GetTeamLiveRequestObject) (api.GetTeamLiveResponseObject, error) {
	u, tm := userOf(ctx).id, s.teams
	// Under the lock, so no change slips in between the first message and the subscription.
	tm.mu.Lock()
	defer tm.mu.Unlock()
	t, ok, err := tm.member(ctx, req.Id, u, deref(req.Params.After))
	if err != nil {
		return nil, err
	}
	if !ok {
		return api.GetTeamLive404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	}
	sub := &teamSub{user: u, ch: make(chan api.Team, 32)}
	sub.ch <- t
	if t.Ended {
		close(sub.ch)
	} else {
		tm.subs[req.Id] = append(tm.subs[req.Id], sub)
	}
	return teamLive{r: requestOf(ctx), sub: sub, drop: func() {
		tm.mu.Lock()
		defer tm.mu.Unlock()
		tm.subs[req.Id] = slices.DeleteFunc(tm.subs[req.Id], func(s *teamSub) bool { return s == sub })
	}}, nil
}

// teamLive upgrades to the WebSocket and sends sub's changes until the team closes it or the client goes.
type teamLive struct {
	r    *http.Request
	sub  *teamSub
	drop func()
}

func (l teamLive) VisitGetTeamLiveResponse(w http.ResponseWriter) error {
	defer l.drop()
	c, err := websocket.Accept(w, l.r, nil)
	if err != nil {
		return nil // Accept has answered the client
	}
	defer c.CloseNow()
	ctx := c.CloseRead(context.Background()) // done once the client goes; the client sends nothing
	ping := time.NewTicker(time.Minute)
	defer ping.Stop()
	for {
		select {
		case t, open := <-l.sub.ch:
			if !open {
				return c.Close(websocket.StatusNormalClosure, "")
			}
			t.Me = l.sub.user
			wctx, cancel := context.WithTimeout(ctx, 20*time.Second)
			err := writeJSONMessage(wctx, c, t)
			cancel()
			if err != nil {
				return nil
			}
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
			err := c.Ping(pctx)
			cancel()
			if err != nil {
				return nil
			}
		case <-ctx.Done():
			return nil
		}
	}
}

func writeJSONMessage(ctx context.Context, c *websocket.Conn, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.Write(ctx, websocket.MessageText, b)
}
