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
	initiator bigint REFERENCES users ON DELETE SET NULL, -- NULL once their account is deleted
	created_at timestamptz NOT NULL DEFAULT now(),
	ended_at timestamptz
);
ALTER TABLE teams ALTER COLUMN initiator DROP NOT NULL; -- tables made before 注销账号 existed
CREATE UNIQUE INDEX IF NOT EXISTS teams_active_code ON teams (code) WHERE ended_at IS NULL;
CREATE TABLE IF NOT EXISTS team_members (
	team_id bigint NOT NULL REFERENCES teams ON DELETE CASCADE,
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
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
CREATE INDEX IF NOT EXISTS team_positions_team ON team_positions (team_id, seq);
CREATE TABLE IF NOT EXISTS team_images (
	id text PRIMARY KEY,
	team_id bigint NOT NULL REFERENCES teams ON DELETE CASCADE,
	user_id bigint REFERENCES users ON DELETE SET NULL,
	original boolean NOT NULL DEFAULT true
);
CREATE INDEX IF NOT EXISTS team_images_team ON team_images (team_id);
CREATE TABLE IF NOT EXISTS team_messages (
	seq bigint PRIMARY KEY DEFAULT nextval('team_positions_seq_seq'), -- one cursor for positions and messages
	team_id bigint NOT NULL REFERENCES teams ON DELETE CASCADE,
	user_id bigint REFERENCES users ON DELETE SET NULL,
	time bigint NOT NULL,
	kind text NOT NULL,
	text text,
	lat double precision,
	lon double precision,
	image text REFERENCES team_images
);
ALTER TABLE team_messages ADD COLUMN IF NOT EXISTS along double precision[]; -- the sender's 沿轨里程 on the 队伍轨迹
DELETE FROM team_messages WHERE kind = 'sos'; -- 一键求助 is gone (#124)
ALTER TABLE team_messages DROP COLUMN IF EXISTS battery; -- only 一键求助 carried it
CREATE INDEX IF NOT EXISTS team_messages_team ON team_messages (team_id, seq);
-- 队伍轨迹 (teamtrack.go): the 发起人's snapshot, gone at 结束行程; the version counts every change to it.
ALTER TABLE teams ADD COLUMN IF NOT EXISTS track_version bigint NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS team_tracks (
	team_id bigint PRIMARY KEY REFERENCES teams ON DELETE CASCADE,
	uuid text NOT NULL,
	name text NOT NULL,
	reversed boolean NOT NULL,
	start double precision NOT NULL,
	points jsonb NOT NULL
);
-- 昵称 (#166) replaces the name given per team: once, each account takes the latest name it gave itself (not the
-- 尾号 default), cut to 12; fillNicknames gives the rest a default. Names are then read from users.
DO $$ BEGIN
	IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'team_members' AND column_name = 'name') THEN
		UPDATE users SET nickname = left(n.name, 12) FROM (
			SELECT DISTINCT ON (user_id) user_id, btrim(name) AS name FROM (
				SELECT user_id, name, joined_at AS at FROM team_members
				UNION ALL SELECT user_id, name, to_timestamp(time) FROM team_messages WHERE user_id IS NOT NULL) given
			WHERE btrim(name) <> '' AND btrim(name) NOT LIKE '尾号%'
			ORDER BY user_id, at DESC) n
		WHERE users.id = n.user_id AND users.nickname IS NULL;
		ALTER TABLE team_members DROP COLUMN name;
		ALTER TABLE team_messages DROP COLUMN IF EXISTS name;
	END IF;
END $$;`

// teamStore keeps teams; pgTeams in production, in memory in tests.
// ponytail: a team left on the way by create or join (the caller was in another) isn't broadcast; its
// sockets hear of it on their next reconnect. That only happens once the app lost track of its team.
type teamStore interface {
	// create makes a team with code, user its 发起人 and first member, leaving any other active team;
	// ok is false if an active team has the code already.
	create(ctx context.Context, code string, user int64) (id int64, ok bool, err error)
	// join adds user to the active team with code (as it is if already in), leaving any other active team.
	join(ctx context.Context, code string, user int64) (id int64, ok bool, err error)
	// team is the team with each member's positions, and the messages, stored after cursor after. Members
	// and senders go by their nickname as it is now.
	team(ctx context.Context, id, after int64) (api.Team, bool, error)
	// activeTeams are the teams user is in whose trip hasn't ended.
	activeTeams(ctx context.Context, user int64) ([]int64, error)
	// leave drops the member and their positions; the last one out ends the team.
	leave(ctx context.Context, id, user int64) error
	// end ends the trip: the code is free again and nobody shares.
	end(ctx context.Context, id int64) error
	setSharing(ctx context.Context, id, user int64, sharing bool) error
	addPositions(ctx context.Context, id, user int64, ps []api.Position) error
	// addMessage stores m from user in team id, setting its seq and from.
	addMessage(ctx context.Context, id, user int64, m api.Message) (api.Message, error)
	addImage(ctx context.Context, id, user int64, image string) error
	// image reports whether team id has image, and whether its original is still kept.
	image(ctx context.Context, id int64, image string) (found, original bool, err error)
	// pruneImages marks gone the originals of teams ended before t, returning their ids.
	pruneImages(ctx context.Context, t time.Time) ([]string, error)
	// setTrack makes tr team id's 队伍轨迹, replacing any, under a new version.
	setTrack(ctx context.Context, id int64, tr api.TeamTrackRequest) error
	// track is team id's 队伍轨迹 with its points; ok is false if it has none.
	track(ctx context.Context, id int64) (tr api.TeamTrack, ok bool, err error)
	// dropTrack removes team id's 队伍轨迹 under a new version.
	dropTrack(ctx context.Context, id int64) error
}

type pgTeams struct{ db *pgxpool.Pool }

// leaveOthers is leave for every active team of user's but keep, in tx.
func leaveOthers(ctx context.Context, tx pgx.Tx, user, keep int64) error {
	_, err := tx.Exec(ctx, `WITH gone AS (
		DELETE FROM team_members m USING teams t
		WHERE m.team_id = t.id AND m.user_id = $1 AND t.id <> $2 AND t.ended_at IS NULL RETURNING t.id)
		UPDATE teams SET ended_at = now() WHERE id IN (SELECT id FROM gone)
		AND NOT EXISTS (SELECT 1 FROM team_members WHERE team_id = teams.id AND user_id <> $1)`, user, keep)
	if err != nil {
		return err
	}
	return dropEndedTracks(ctx, tx)
}

// dropEndedTracks deletes the 队伍轨迹 of teams whose trip has ended (spec §2.11), in tx.
// ponytail: every ended team's, not just the one at hand; there are none left over but those, so it stays small.
func dropEndedTracks(ctx context.Context, tx pgx.Tx) error {
	_, err := tx.Exec(ctx, "DELETE FROM team_tracks k USING teams t WHERE k.team_id = t.id AND t.ended_at IS NOT NULL")
	return err
}

func (p pgTeams) create(ctx context.Context, code string, user int64) (id int64, ok bool, err error) {
	err = pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if err := leaveOthers(ctx, tx, user, 0); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, "INSERT INTO teams (code, initiator) VALUES ($1, $2) RETURNING id", code, user).Scan(&id); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "INSERT INTO team_members (team_id, user_id) VALUES ($1, $2)", id, user)
		return err
	})
	if pe := (*pgconn.PgError)(nil); errors.As(err, &pe) && pe.Code == "23505" { // the code is taken
		return 0, false, nil
	}
	return id, err == nil, err
}

func (p pgTeams) join(ctx context.Context, code string, user int64) (id int64, ok bool, err error) {
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
		_, err = tx.Exec(ctx, "INSERT INTO team_members (team_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING", id, user)
		return err
	})
	return id, ok && err == nil, err
}

func (p pgTeams) team(ctx context.Context, id, after int64) (t api.Team, ok bool, err error) {
	err = p.db.QueryRow(ctx, `SELECT id, code, coalesce(initiator, 0), ended_at IS NOT NULL,
		greatest((SELECT coalesce(max(seq), 0) FROM team_positions), (SELECT coalesce(max(seq), 0) FROM team_messages)) FROM teams WHERE id = $1`, id).
		Scan(&t.Id, &t.Code, &t.Initiator, &t.Ended, &t.Cursor)
	if errors.Is(err, pgx.ErrNoRows) {
		return t, false, nil
	}
	if err != nil {
		return t, false, err
	}
	rows, _ := p.db.Query(ctx, "SELECT m.user_id, u.nickname, m.sharing FROM team_members m JOIN users u ON u.id = m.user_id WHERE m.team_id = $1 ORDER BY m.joined_at", id)
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
	if err != nil {
		return t, false, err
	}
	rows, _ = p.db.Query(ctx, "SELECT "+messageColumns+" FROM "+messageFrom+" WHERE m.team_id = $1 AND m.seq > $2 AND m.seq <= $3 ORDER BY m.seq", id, after, t.Cursor)
	if t.Messages, err = pgx.CollectRows(rows, scanMessage); err != nil {
		return t, false, err
	}
	var ref api.TeamTrackRef
	err = p.db.QueryRow(ctx, "SELECT "+trackRefColumns+" "+trackOf, id).Scan(&ref.Version, &ref.Uuid, &ref.Name, &ref.Reversed, &ref.Start)
	if err == nil {
		t.Track = &ref
	}
	if errors.Is(err, pgx.ErrNoRows) {
		err = nil
	}
	return t, err == nil, err
}

func (p pgTeams) activeTeams(ctx context.Context, user int64) ([]int64, error) {
	rows, _ := p.db.Query(ctx, "SELECT t.id FROM team_members m JOIN teams t ON t.id = m.team_id WHERE m.user_id = $1 AND t.ended_at IS NULL", user)
	return pgx.CollectRows(rows, pgx.RowTo[int64])
}

func (p pgTeams) leave(ctx context.Context, id, user int64) error {
	return pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "DELETE FROM team_members WHERE team_id = $1 AND user_id = $2", id, user); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "UPDATE teams SET ended_at = now() WHERE id = $1 AND ended_at IS NULL AND NOT EXISTS (SELECT 1 FROM team_members WHERE team_id = $1)", id); err != nil {
			return err
		}
		return dropEndedTracks(ctx, tx)
	})
}

func (p pgTeams) end(ctx context.Context, id int64) error {
	return pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "UPDATE teams SET ended_at = now() WHERE id = $1 AND ended_at IS NULL", id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "UPDATE team_members SET sharing = false WHERE team_id = $1", id); err != nil {
			return err
		}
		return dropEndedTracks(ctx, tx)
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
	store  teamStore
	images string // directory of the 队伍对话's photos (chat.go)
	mu     sync.Mutex
	subs   map[int64][]*teamSub // by team id
}

type teamSub struct {
	user int64
	ch   chan api.Team // closed when the socket should end
}

func newTeams(store teamStore, images string) *teams {
	return &teams{store: store, images: images, subs: map[int64][]*teamSub{}}
}

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

func (s *server) PostTeam(ctx context.Context, req api.PostTeamRequestObject) (api.PostTeamResponseObject, error) {
	u := userOf(ctx).id
	// ponytail: random codes, retried on a clash; plenty while active teams are far fewer than 10000.
	for range 20 {
		n, _ := rand.Int(rand.Reader, big.NewInt(10000))
		id, ok, err := s.teams.store.create(ctx, fmt.Sprintf("%04d", n), u)
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
	if !teamCode.MatchString(req.Body.Code) {
		return api.PostTeamJoin400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u := userOf(ctx).id
	id, found, err := s.teams.store.join(ctx, req.Body.Code, u)
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

func deref[T any](p *T) (v T) {
	if p != nil {
		v = *p
	}
	return
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
