package main

// 同步 and 注销账号 (spec §2.12). Each 轨迹, 标注组 and 标注 is keyed by the id the phone made for it. Points and
// places are written once; attributes are overwritten one by one in the order the server receives them
// (last write wins), each write taking the next sync_rev, which is also the pull cursor. Deleting leaves a
// 删除标记 (deleted, contents wiped) so other phones hear of it. Photos live on local disk like the
// 队伍对话's, at most quota bytes per account.

import (
	"bytes"
	"context"
	"errors"
	"image"
	_ "image/jpeg"
	"io"
	"log"
	"os"
	"path/filepath"
	"slices"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"stars-trail/server/api"
)

const syncSchema = `
CREATE SEQUENCE IF NOT EXISTS sync_rev;
CREATE TABLE IF NOT EXISTS sync_tracks (
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	id text NOT NULL,
	rev bigint NOT NULL DEFAULT nextval('sync_rev'),
	started_at bigint NOT NULL,
	ended_at bigint NOT NULL,
	planned boolean NOT NULL,
	points json NOT NULL,
	name text NOT NULL DEFAULT '',
	datum text NOT NULL DEFAULT 'WGS84',
	deleted boolean NOT NULL DEFAULT false,
	PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS sync_tracks_rev ON sync_tracks (user_id, rev);
CREATE TABLE IF NOT EXISTS sync_waypoints (
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	id text NOT NULL,
	rev bigint NOT NULL DEFAULT nextval('sync_rev'),
	track text NOT NULL DEFAULT '',
	time bigint NOT NULL,
	lat double precision NOT NULL,
	lon double precision NOT NULL,
	ele double precision,
	name text NOT NULL DEFAULT '',
	description text NOT NULL DEFAULT '',
	photo text NOT NULL DEFAULT '',
	deleted boolean NOT NULL DEFAULT false,
	PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS sync_waypoints_rev ON sync_waypoints (user_id, rev);
CREATE TABLE IF NOT EXISTS sync_groups (
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	id text NOT NULL,
	rev bigint NOT NULL DEFAULT nextval('sync_rev'),
	name text NOT NULL,
	deleted boolean NOT NULL DEFAULT false,
	PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS sync_groups_rev ON sync_groups (user_id, rev);
ALTER TABLE sync_tracks ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT ''; -- 「来自 佳明 fēnix 7」 (#89)
ALTER TABLE sync_waypoints ADD COLUMN IF NOT EXISTS grp text NOT NULL DEFAULT ''; -- its 标注组 (#121)
CREATE TABLE IF NOT EXISTS sync_photos (
	id text PRIMARY KEY,
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	bytes bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS sync_photos_user ON sync_photos (user_id);`

// syncPage is how many tracks, 标注组 and 标注 (each) one pull returns at most.
// ponytail: a page of tracks carries all their points (a long one is a few MB); fewer per page if pulls time out.
const syncPage = 50

// deletedUser is what a deleted account's 队伍对话 messages show as sender.
const deletedUser = "已注销用户"

// cloud keeps the synced data in PostgreSQL and the photos in a directory.
type cloud struct {
	db     *pgxpool.Pool
	photos string
	quota  int64 // photo bytes per account
}

func newCloud(db *pgxpool.Pool, images string, quota int64) *cloud {
	dir := filepath.Join(images, "sync")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Fatal(err)
	}
	return &cloud{db: db, photos: dir, quota: quota}
}

func (c *cloud) photoPath(id string) string { return filepath.Join(c.photos, id+".jpg") }

var errBadChange = errors.New("bad change")

func (s *server) GetSync(ctx context.Context, req api.GetSyncRequestObject) (api.GetSyncResponseObject, error) {
	u, after := userOf(ctx).id, deref(req.Params.After)
	res := api.Sync{Cursor: after, Tracks: []api.SyncTrack{}, Groups: []api.SyncGroup{}, Waypoints: []api.SyncWaypoint{}}
	var trackRevs, groupRevs, wptRevs []int64
	rows, _ := s.cloud.db.Query(ctx, `SELECT rev, id, started_at, ended_at, planned, points, name, datum, deleted, source,
		EXISTS (SELECT 1 FROM public_tracks p WHERE p.user_id = t.user_id AND p.id = t.id)
		FROM sync_tracks t WHERE user_id = $1 AND rev > $2 ORDER BY rev LIMIT $3`, u, after, syncPage)
	var rev int64
	var tr api.SyncTrack
	if _, err := pgx.ForEachRow(rows, []any{&rev, &tr.Id, &tr.StartedAt, &tr.EndedAt, &tr.Planned, &tr.Points, &tr.Name, &tr.Datum, &tr.Deleted, &tr.Source, &tr.Public}, func() error {
		trackRevs, res.Tracks = append(trackRevs, rev), append(res.Tracks, tr)
		return nil
	}); err != nil {
		return nil, err
	}
	rows, _ = s.cloud.db.Query(ctx, `SELECT rev, id, name, deleted FROM sync_groups WHERE user_id = $1 AND rev > $2 ORDER BY rev LIMIT $3`, u, after, syncPage)
	var g api.SyncGroup
	if _, err := pgx.ForEachRow(rows, []any{&rev, &g.Id, &g.Name, &g.Deleted}, func() error {
		groupRevs, res.Groups = append(groupRevs, rev), append(res.Groups, g)
		return nil
	}); err != nil {
		return nil, err
	}
	rows, _ = s.cloud.db.Query(ctx, `SELECT rev, id, track, grp, time, lat, lon, ele, name, description, photo, deleted
		FROM sync_waypoints WHERE user_id = $1 AND rev > $2 ORDER BY rev LIMIT $3`, u, after, syncPage)
	var w api.SyncWaypoint
	if _, err := pgx.ForEachRow(rows, []any{&rev, &w.Id, &w.Track, &w.Group, &w.Time, &w.Lat, &w.Lon, &w.Ele, &w.Name, &w.Description, &w.Photo, &w.Deleted}, func() error {
		wptRevs, res.Waypoints = append(wptRevs, rev), append(res.Waypoints, w)
		w.Ele = nil
		return nil
	}); err != nil {
		return nil, err
	}
	// A full page may have more behind it, so the cursor stops at its last row and the other tables'
	// rows after that wait for the next pull.
	all := [][]int64{trackRevs, groupRevs, wptRevs}
	for _, r := range slices.Concat(all...) {
		res.Cursor = max(res.Cursor, r)
	}
	for _, revs := range all {
		if len(revs) == syncPage {
			res.More, res.Cursor = true, min(res.Cursor, revs[len(revs)-1])
		}
	}
	res.Tracks = res.Tracks[:countUpTo(trackRevs, res.Cursor)]
	res.Groups = res.Groups[:countUpTo(groupRevs, res.Cursor)]
	res.Waypoints = res.Waypoints[:countUpTo(wptRevs, res.Cursor)]
	return api.GetSync200JSONResponse(res), nil
}

// existing looks up a synced row of table, locking it; photo is only read on sync_waypoints.
func existing(ctx context.Context, tx pgx.Tx, table string, user int64, id string) (found, deleted bool, photo string, err error) {
	col := "''"
	if table == "sync_waypoints" {
		col = "photo"
	}
	err = tx.QueryRow(ctx, "SELECT deleted, "+col+" FROM "+table+" WHERE user_id = $1 AND id = $2 FOR UPDATE", user, id).Scan(&deleted, &photo)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, false, "", nil
	}
	return err == nil, deleted, photo, err
}

func ownsPhoto(ctx context.Context, q interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}, user int64, photo string) (ok bool, err error) {
	err = q.QueryRow(ctx, "SELECT EXISTS (SELECT 1 FROM sync_photos WHERE id = $1 AND user_id = $2)", photo, user).Scan(&ok)
	return
}

// countUpTo is how many of the ascending revs are at most cursor.
func countUpTo(revs []int64, cursor int64) int {
	n, _ := slices.BinarySearch(revs, cursor+1)
	return n
}

func (s *server) PostSync(ctx context.Context, req api.PostSyncRequestObject) (api.PostSyncResponseObject, error) {
	u := userOf(ctx).id
	var gone []string // photos no 标注 uses any more
	err := pgx.BeginFunc(ctx, s.cloud.db, func(tx pgx.Tx) error {
		gone = nil
		// One push per account at a time, so its revs commit in order: a pull never passes a rev that
		// another push still has in flight (it would never see it).
		if _, err := tx.Exec(ctx, "SELECT 1 FROM users WHERE id = $1 FOR UPDATE", u); err != nil {
			return err
		}
		for _, t := range req.Body.Tracks {
			if !imageID.MatchString(t.Id) {
				return errBadChange
			}
			del := t.Deleted != nil && *t.Deleted
			found, deleted, _, err := existing(ctx, tx, "sync_tracks", u, t.Id)
			switch {
			case err != nil:
				return err
			case !found: // NOT NULL turns missing fixed parts into errBadChange below
				_, err = tx.Exec(ctx, `INSERT INTO sync_tracks (user_id, id, started_at, ended_at, planned, points, name, datum, deleted, source)
					VALUES ($1, $2, $3, $4, $5, $6, coalesce($7, ''), coalesce($8, 'WGS84'), $9, coalesce($10, ''))`, u, t.Id, t.StartedAt, t.EndedAt, t.Planned, t.Points, t.Name, t.Datum, del, t.Source)
			case !deleted:
				_, err = tx.Exec(ctx, `UPDATE sync_tracks SET rev = nextval('sync_rev'),
					name = CASE WHEN $5 THEN '' ELSE coalesce($3, name) END,
					source = CASE WHEN $5 THEN '' ELSE source END,
					datum = coalesce($4, datum),
					points = CASE WHEN $5 THEN '[]' ELSE points END,
					deleted = $5
					WHERE user_id = $1 AND id = $2`, u, t.Id, t.Name, t.Datum, del)
			}
			if err != nil {
				return err
			}
			switch {
			case deleted: // a 删除标记 stays as it is
			case del: // withdrawn with it
				err = publish(ctx, tx, u, t.Id, new(false))
			case t.Public != nil || t.Datum != nil:
				err = publish(ctx, tx, u, t.Id, t.Public)
			}
			if err != nil {
				return err
			}
		}
		for _, g := range deref(req.Body.Groups) {
			if !imageID.MatchString(g.Id) {
				return errBadChange
			}
			del := g.Deleted != nil && *g.Deleted
			found, deleted, _, err := existing(ctx, tx, "sync_groups", u, g.Id)
			switch {
			case err != nil:
				return err
			case !found: // no name: not_null_violation
				_, err = tx.Exec(ctx, `INSERT INTO sync_groups (user_id, id, name, deleted) VALUES ($1, $2, CASE WHEN $4 THEN '' ELSE $3 END, $4)`, u, g.Id, g.Name, del)
			case !deleted:
				_, err = tx.Exec(ctx, `UPDATE sync_groups SET rev = nextval('sync_rev'),
					name = CASE WHEN $4 THEN '' ELSE coalesce($3, name) END, deleted = $4
					WHERE user_id = $1 AND id = $2`, u, g.Id, g.Name, del)
			}
			if err != nil {
				return err
			}
		}
		for _, w := range req.Body.Waypoints {
			if !imageID.MatchString(w.Id) {
				return errBadChange
			}
			del := w.Deleted != nil && *w.Deleted
			if w.Photo != nil && *w.Photo != "" && !del {
				if ok, err := ownsPhoto(ctx, tx, u, *w.Photo); err != nil {
					return err
				} else if !ok {
					return errNoImage
				}
			}
			found, deleted, old, err := existing(ctx, tx, "sync_waypoints", u, w.Id)
			switch {
			case err != nil:
				return err
			case !found:
				_, err = tx.Exec(ctx, `INSERT INTO sync_waypoints (user_id, id, track, time, lat, lon, ele, name, description, photo, deleted, grp)
					VALUES ($1, $2, coalesce($3, ''), $4, $5, $6, $7, coalesce($8, ''), coalesce($9, ''), coalesce($10, ''), $11, coalesce($12, ''))`,
					u, w.Id, w.Track, w.Time, w.Lat, w.Lon, w.Ele, w.Name, w.Description, w.Photo, del, w.Group)
			case !deleted:
				_, err = tx.Exec(ctx, `UPDATE sync_waypoints SET rev = nextval('sync_rev'),
					name = CASE WHEN $6 THEN '' ELSE coalesce($3, name) END,
					description = CASE WHEN $6 THEN '' ELSE coalesce($4, description) END,
					photo = CASE WHEN $6 THEN '' ELSE coalesce($5, photo) END,
					grp = CASE WHEN $6 THEN '' ELSE coalesce($7, grp) END,
					deleted = $6
					WHERE user_id = $1 AND id = $2`, u, w.Id, w.Name, w.Description, w.Photo, del, w.Group)
				if old != "" && (del || w.Photo != nil && *w.Photo != old) {
					gone = append(gone, old)
				}
			}
			if err != nil {
				return err
			}
		}
		_, err := tx.Exec(ctx, "DELETE FROM sync_photos WHERE user_id = $1 AND id = ANY($2)", u, gone)
		return err
	})
	var pe *pgconn.PgError
	switch {
	case errors.Is(err, errBadChange) || errors.As(err, &pe) && pe.Code == "23502": // not_null_violation
		return api.PostSync400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	case errors.Is(err, errNoImage):
		return api.PostSync400JSONResponse{Error: api.ErrorCodeImageNotFound}, nil
	case err != nil:
		return nil, err
	}
	for _, id := range gone {
		os.Remove(s.cloud.photoPath(id))
	}
	return api.PostSync204Response{}, nil
}

// ponytail: a photo uploaded but never set on a 标注 (the phone died in between) keeps its room in the
// quota; sweep photos no 标注 uses if that adds up.
func (s *server) PostSyncPhoto(ctx context.Context, req api.PostSyncPhotoRequestObject) (api.PostSyncPhotoResponseObject, error) {
	u := userOf(ctx).id
	b, err := io.ReadAll(req.Body) // capped at 1 MB by withMiddleware
	if err != nil {
		return api.PostSyncPhoto400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil || format != "jpeg" || cfg.Width > maxImageSide || cfg.Height > maxImageSide {
		return api.PostSyncPhoto400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	id := newImageID()
	// Counted and stored under the account's row lock, so parallel uploads can't pass the quota together.
	full := false
	err = pgx.BeginFunc(ctx, s.cloud.db, func(tx pgx.Tx) error {
		var used int64
		if _, err := tx.Exec(ctx, "SELECT 1 FROM users WHERE id = $1 FOR UPDATE", u); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, "SELECT coalesce(sum(bytes), 0) FROM sync_photos WHERE user_id = $1", u).Scan(&used); err != nil {
			return err
		}
		if full = used+int64(len(b)) > s.cloud.quota; full {
			return nil
		}
		if err := os.WriteFile(s.cloud.photoPath(id), b, 0o644); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "INSERT INTO sync_photos (id, user_id, bytes) VALUES ($1, $2, $3)", id, u, len(b))
		return err
	})
	if err != nil {
		os.Remove(s.cloud.photoPath(id))
		return nil, err
	}
	if full {
		return api.PostSyncPhoto413JSONResponse{Error: api.ErrorCodePhotoQuotaExceeded, QuotaBytes: &s.cloud.quota}, nil
	}
	return api.PostSyncPhoto200JSONResponse{Photo: id}, nil
}

func (s *server) GetSyncPhoto(ctx context.Context, req api.GetSyncPhotoRequestObject) (api.GetSyncPhotoResponseObject, error) {
	notFound := api.GetSyncPhoto404JSONResponse{Error: api.ErrorCodeImageNotFound}
	if !imageID.MatchString(req.Photo) { // it becomes a file name
		return notFound, nil
	}
	if ok, err := ownsPhoto(ctx, s.cloud.db, userOf(ctx).id, req.Photo); err != nil {
		return nil, err
	} else if !ok {
		return notFound, nil
	}
	b, err := os.ReadFile(s.cloud.photoPath(req.Photo))
	if err != nil {
		return nil, err
	}
	return api.GetSyncPhoto200ImagejpegResponse{Body: bytes.NewReader(b), ContentLength: int64(len(b))}, nil
}

// DeleteMe is 注销账号: everything of the caller's goes, their 队伍对话 messages stay as 已注销用户 with
// the content removed, and teams they started lose their 发起人 (nobody can 结束行程; the last one out ends it).
func (s *server) DeleteMe(ctx context.Context, _ api.DeleteMeRequestObject) (api.DeleteMeResponseObject, error) {
	u := userOf(ctx).id
	var photos, images []string
	var avatar *string
	err := pgx.BeginFunc(ctx, s.cloud.db, func(tx pgx.Tx) error {
		if err := leaveOthers(ctx, tx, u, 0); err != nil {
			return err
		}
		// A system note stays one, its words gone too (they may name them).
		if _, err := tx.Exec(ctx, `UPDATE team_messages SET user_id = NULL, kind = CASE WHEN kind = 'system' THEN 'system' ELSE 'text' END, text = '消息已删除',
			lat = NULL, lon = NULL, image = NULL WHERE user_id = $1`, u); err != nil {
			return err
		}
		// Someone else's message may show one of these photos: it loses it.
		if _, err := tx.Exec(ctx, "UPDATE team_messages SET image = NULL WHERE image IN (SELECT id FROM team_images WHERE user_id = $1)", u); err != nil {
			return err
		}
		rows, _ := tx.Query(ctx, "DELETE FROM team_images WHERE user_id = $1 RETURNING id", u)
		var err error
		if images, err = pgx.CollectRows(rows, pgx.RowTo[string]); err != nil {
			return err
		}
		rows, _ = tx.Query(ctx, "DELETE FROM sync_photos WHERE user_id = $1 RETURNING id", u)
		if photos, err = pgx.CollectRows(rows, pgx.RowTo[string]); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "UPDATE teams SET initiator = NULL WHERE initiator = $1", u); err != nil {
			return err
		}
		// Sessions, synced tracks, 标注组 and 标注, memberships and positions go with it (ON DELETE CASCADE).
		if err := tx.QueryRow(ctx, "DELETE FROM users WHERE id = $1 RETURNING avatar", u).Scan(&avatar); !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, id := range photos {
		os.Remove(s.cloud.photoPath(id))
	}
	for _, id := range images {
		orig, thumb := s.teams.imagePaths(id)
		os.Remove(orig)
		os.Remove(thumb)
	}
	if avatar != nil {
		os.Remove(s.teams.avatarPath(*avatar))
	}
	return api.DeleteMe204Response{}, nil
}
