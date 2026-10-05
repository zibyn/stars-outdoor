package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"slices"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"stars-outdoor/server/api"
)

func me(t *testing.T, h http.Handler, token string) api.Me {
	t.Helper()
	w := do(h, "GET", "/v1/me", token, "")
	var m api.Me
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &m) != nil {
		t.Fatalf("me: %d %s", w.Code, w.Body)
	}
	return m
}

// A default is a name from 附录 A and two digits, never the number's 尾号.
func isDefaultNickname(n string) bool {
	m := regexp.MustCompile(`^(.+)([0-9]{2})$`).FindStringSubmatch(n)
	return m != nil && slices.Contains(defaultNames, m[1])
}

func TestNewAccountGetsADefaultNicknameAndCanChangeIt(t *testing.T) {
	h := teamHandler(t)
	a := login(t, h, "13800138000")
	if n := me(t, h, a).Nickname; !isDefaultNickname(n) {
		t.Fatalf("default: %q", n)
	}
	if w := do(h, "PUT", "/v1/me/nickname", a, `{"nickname":"  小李🏔  "}`); w.Code != 200 || !strings.Contains(w.Body.String(), `"nickname":"小李🏔"`) {
		t.Fatalf("rename: %d %s", w.Code, w.Body)
	}
	if n := me(t, h, a).Nickname; n != "小李🏔" {
		t.Fatalf("stored: %q", n)
	}
	// Logging in again keeps it.
	if n := me(t, h, login(t, h, "13800138000")).Nickname; n != "小李🏔" {
		t.Fatalf("after login: %q", n)
	}
	for _, body := range []string{`{"nickname":"   "}`, `{"nickname":"` + strings.Repeat("名", 13) + `"}`, `{}`} {
		if w := do(h, "PUT", "/v1/me/nickname", a, body); w.Code != 400 {
			t.Fatalf("%s: %d %s", body, w.Code, w.Body)
		}
	}
	if w := do(h, "PUT", "/v1/me/nickname", a, `{"nickname":"`+strings.Repeat("名", 12)+`"}`); w.Code != 200 {
		t.Fatalf("12: %d %s", w.Code, w.Body)
	}
}

// #184: A renames; B's socket hears it at once, and A's older messages carry the new name; no system message.
func TestRenameReachesTheTeamAtOnce(t *testing.T) {
	h := teamHandler(t)
	srv := httptest.NewServer(h)
	defer srv.Close()
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	do(h, "POST", path(tm, "/messages"), a, `{"kind":"text","text":"出发"}`)
	c := dial(t, srv, path(tm, "/live"), b)
	snap := next(t, c)
	// Messages[0] is b's 加入了.
	if member(snap, 1).Name != me(t, h, a).Nickname || snap.Messages[1].Name != member(snap, 1).Name {
		t.Fatalf("names before: %+v", snap)
	}
	do(h, "PUT", "/v1/me/nickname", a, `{"nickname":"老王"}`)
	ev := next(t, c)
	if member(ev, 1).Name != "老王" || len(ev.Messages) != 0 {
		t.Fatalf("rename event: %+v", ev)
	}
	got := teamOf(t, do(h, "GET", path(tm, ""), b, ""))
	if len(got.Messages) != 2 || got.Messages[1].Name != "老王" {
		t.Fatalf("messages: %+v", got.Messages)
	}
	// Not in a team: nothing to tell, still fine.
	if w := do(h, "PUT", "/v1/me/nickname", login(t, h, "13700137000"), `{"nickname":"独行"}`); w.Code != 200 {
		t.Fatalf("no team: %d", w.Code)
	}
}

// #184 迁移: each account's latest own name in a team (not 尾号…), cut to 12, else a default; the per-team
// name columns go.
func TestNicknameMigration(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	db, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	// The database as it was: per-team names, no nickname.
	if _, err := db.Exec(ctx, "DROP TABLE IF EXISTS public_tracks, sync_photos, sync_waypoints, sync_groups, sync_tracks, team_tracks, team_messages, team_images, team_positions, team_members, teams, sessions, users CASCADE;"+
		usersSchema+teamsSchema+`
		ALTER TABLE users DROP COLUMN nickname;
		ALTER TABLE team_members ADD COLUMN name text NOT NULL DEFAULT '';
		ALTER TABLE team_messages ADD COLUMN name text NOT NULL DEFAULT '';
		INSERT INTO users (id, phone) VALUES (1, '13800138000'), (2, '13900139000'), (3, '13700137000'), (4, '13600136000');
		INSERT INTO teams (id, code, initiator, created_at) VALUES (1, '1111', 1, now() - interval '2 days'), (2, '2222', 1, now());
		INSERT INTO team_members (team_id, user_id, name, joined_at) VALUES
			(1, 1, '老王', now() - interval '2 days'), (2, 1, '王哥', now()),
			(1, 2, ' 尾号9000', now() - interval '2 days'),
			(2, 3, '一二三四五六七八九十一二三四', now());
		INSERT INTO team_messages (team_id, user_id, name, time, kind, text) VALUES
			(1, 1, '老王', extract(epoch from now() - interval '2 days')::bigint, 'text', 'a'),
			(1, 2, '尾号9000', extract(epoch from now())::bigint, 'text', 'b');`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, usersSchema+teamsSchema); err != nil {
		t.Fatal(err)
	}
	if err := fillNicknames(ctx, db); err != nil {
		t.Fatal(err)
	}
	got := map[int64]string{}
	rows, _ := db.Query(ctx, "SELECT id, nickname FROM users")
	var id int64
	var n string
	for rows.Next() {
		rows.Scan(&id, &n)
		got[id] = n
	}
	if got[1] != "王哥" || !isDefaultNickname(got[2]) || got[3] != "一二三四五六七八九十一二" || !isDefaultNickname(got[4]) {
		t.Fatalf("nicknames: %v", got)
	}
	var cols int
	db.QueryRow(ctx, "SELECT count(*) FROM information_schema.columns WHERE table_name IN ('team_members', 'team_messages') AND column_name = 'name'").Scan(&cols)
	if cols != 0 {
		t.Fatalf("name columns left: %d", cols)
	}
	// Run again (every start): nothing changes.
	if _, err := db.Exec(ctx, usersSchema+teamsSchema); err != nil {
		t.Fatal(err)
	}
	if err := fillNicknames(ctx, db); err != nil {
		t.Fatal(err)
	}
	if db.QueryRow(ctx, "SELECT nickname FROM users WHERE id = 2").Scan(&n); n != got[2] {
		t.Fatalf("default changed: %q → %q", got[2], n)
	}
}
