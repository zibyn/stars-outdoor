package main

// 队伍轨迹 (spec §2.11): the 发起人 gives the team a track as a snapshot (points and 起算点), which every member
// takes as their 参考轨迹. Each change is a system message in the 队伍对话; 结束行程 deletes the snapshot.

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"reflect"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"stars-outdoor/server/api"
)

// A 队伍轨迹's columns but its points, from the query's tail trackOf (team id $1).
const (
	trackRefColumns = "t.track_version, k.uuid, k.name, k.reversed, k.start"
	trackOf         = "FROM team_tracks k JOIN teams t ON t.id = k.team_id WHERE k.team_id = $1"
)

func (p pgTeams) setTrack(ctx context.Context, id int64, tr api.TeamTrackRequest) error {
	points, err := json.Marshal(tr.Points)
	if err != nil {
		return err
	}
	return pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "UPDATE teams SET track_version = track_version + 1 WHERE id = $1", id); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO team_tracks (team_id, uuid, name, reversed, start, points) VALUES ($1, $2, $3, $4, $5, $6)
			ON CONFLICT (team_id) DO UPDATE SET uuid = $2, name = $3, reversed = $4, start = $5, points = $6`, id, tr.Uuid, tr.Name, tr.Reversed, tr.Start, points)
		return err
	})
}

func (p pgTeams) track(ctx context.Context, id int64) (tr api.TeamTrack, ok bool, err error) {
	var points []byte
	err = p.db.QueryRow(ctx, "SELECT "+trackRefColumns+", k.points "+trackOf, id).Scan(&tr.Version, &tr.Uuid, &tr.Name, &tr.Reversed, &tr.Start, &points)
	if errors.Is(err, pgx.ErrNoRows) {
		return tr, false, nil
	}
	if err != nil {
		return tr, false, err
	}
	return tr, true, json.Unmarshal(points, &tr.Points)
}

func (p pgTeams) dropTrack(ctx context.Context, id int64) error {
	return pgx.BeginFunc(ctx, p.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "DELETE FROM team_tracks WHERE team_id = $1", id); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "UPDATE teams SET track_version = track_version + 1 WHERE id = $1", id)
		return err
	})
}

// validTrack reports whether tr is within TeamTrackRequest's bounds.
func validTrack(tr *api.TeamTrackRequest) bool {
	n := utf8.RuneCountInString(tr.Name)
	return n >= 1 && n <= 100 && len(tr.Uuid) <= 64 && tr.Start >= 0 && !math.IsInf(tr.Start, 0) && !math.IsNaN(tr.Start) &&
		len(tr.Points) >= 2 && len(tr.Points) <= 50000 &&
		!slices.ContainsFunc(tr.Points, func(p api.SyncPoint) bool { return math.Abs(p.Lat) > 90 || math.Abs(p.Lon) > 180 })
}

// trackNote is the 队伍对话's system message for a 队伍轨迹 given (first, or 更换 / 改起算点 when changed), ux-v2 §6.5.
// A change always says which way, so turning it back reads as that rather than as another track.
func trackNote(tr *api.TeamTrackRequest, changed bool) string {
	verb := "设为 "
	if changed {
		verb = "换成 "
	}
	var how []string
	if tr.Reversed {
		how = append(how, "反向")
	} else if changed {
		how = append(how, "正向")
	}
	if tr.Start > 0 {
		how = append(how, "换了起点")
	}
	note := "发起人把队伍轨迹" + verb + tr.Name
	if len(how) > 0 {
		note += "（" + strings.Join(how, "、") + "）"
	}
	return note
}

// systemMessage stores note in team id as a system message from member me.
func (tm *teams) systemMessage(ctx context.Context, id int64, me api.Member, note string) error {
	_, err := tm.store.addMessage(ctx, id, me.Id, api.Message{Kind: api.MessageKindSystem, Text: &note, Name: me.Name, Time: time.Now().Unix()})
	return err
}

func (s *server) PutTeamTrack(ctx context.Context, req api.PutTeamTrackRequestObject) (api.PutTeamTrackResponseObject, error) {
	if !validTrack(req.Body) {
		return api.PutTeamTrack400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u := userOf(ctx).id
	err := s.teams.change(ctx, req.Id, u, func(t api.Team, me api.Member) error {
		switch {
		case t.Ended:
			return errEnded
		case t.Initiator != u:
			return errNotInitiator
		}
		// The same again (起算点 saved unchanged, the same track picked): nothing for the team to hear.
		if t.Track != nil {
			have, ok, err := s.teams.store.track(ctx, req.Id)
			if err != nil {
				return err
			}
			b := req.Body
			if ok && have.Uuid == b.Uuid && have.Name == b.Name && have.Reversed == b.Reversed && have.Start == b.Start && reflect.DeepEqual(have.Points, b.Points) {
				return errNoChange
			}
		}
		if err := s.teams.store.setTrack(ctx, req.Id, *req.Body); err != nil {
			return err
		}
		return s.teams.systemMessage(ctx, req.Id, me, trackNote(req.Body, t.Track != nil))
	})
	switch {
	case errors.Is(err, errNotMember):
		return api.PutTeamTrack404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case errors.Is(err, errEnded):
		return api.PutTeamTrack409JSONResponse(teamEnded), nil
	case errors.Is(err, errNotInitiator):
		return api.PutTeamTrack403JSONResponse{Error: api.ErrorCodeNotInitiator}, nil
	case err != nil:
		return nil, err
	}
	return api.PutTeamTrack204Response{}, nil
}

func (s *server) DeleteTeamTrack(ctx context.Context, req api.DeleteTeamTrackRequestObject) (api.DeleteTeamTrackResponseObject, error) {
	u := userOf(ctx).id
	err := s.teams.change(ctx, req.Id, u, func(t api.Team, me api.Member) error {
		switch {
		case t.Initiator != u:
			return errNotInitiator
		case t.Track == nil: // none, or the trip ended
			return errNoChange
		}
		if err := s.teams.store.dropTrack(ctx, req.Id); err != nil {
			return err
		}
		return s.teams.systemMessage(ctx, req.Id, me, "发起人取消了队伍轨迹")
	})
	switch {
	case errors.Is(err, errNotMember):
		return api.DeleteTeamTrack404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case errors.Is(err, errNotInitiator):
		return api.DeleteTeamTrack403JSONResponse{Error: api.ErrorCodeNotInitiator}, nil
	case err != nil:
		return nil, err
	}
	return api.DeleteTeamTrack204Response{}, nil
}

func (s *server) GetTeamTrack(ctx context.Context, req api.GetTeamTrackRequestObject) (api.GetTeamTrackResponseObject, error) {
	_, ok, err := s.teams.member(ctx, req.Id, userOf(ctx).id, math.MaxInt64)
	if err != nil {
		return nil, err
	}
	if !ok {
		return api.GetTeamTrack404JSONResponse{Error: api.ErrorCodeTeamNotFound}, nil
	}
	tr, ok, err := s.teams.store.track(ctx, req.Id)
	if err != nil {
		return nil, err
	}
	if !ok {
		return api.GetTeamTrack404JSONResponse{Error: api.ErrorCodeNoTeamTrack}, nil
	}
	return api.GetTeamTrack200JSONResponse(tr), nil
}
