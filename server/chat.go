package main

// 队伍对话 and 一键求助 (spec §2.11): messages go out like any team change (teams.change), over the team's
// sockets while the trip runs and by GET /teams/{id} after it. Photos live on local disk (§3.2): the
// original and a thumbnail; 180 days after 结束行程 only the thumbnail stays.

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"log"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"stars-outdoor/server/api"
)

const messageColumns = "seq, user_id, name, time, kind, text, lat, lon, battery, image"

func scanMessage(r pgx.CollectableRow) (m api.Message, err error) {
	return m, r.Scan(&m.Seq, &m.From, &m.Name, &m.Time, &m.Kind, &m.Text, &m.Lat, &m.Lon, &m.Battery, &m.Image)
}

func (p pgTeams) addMessage(ctx context.Context, id, user int64, m api.Message) (api.Message, error) {
	rows, _ := p.db.Query(ctx, `INSERT INTO team_messages (team_id, user_id, name, time, kind, text, lat, lon, battery, image)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING `+messageColumns, id, user, m.Name, m.Time, m.Kind, m.Text, m.Lat, m.Lon, m.Battery, m.Image)
	return pgx.CollectExactlyOneRow(rows, scanMessage)
}

func (p pgTeams) addImage(ctx context.Context, id, user int64, image string) error {
	_, err := p.db.Exec(ctx, "INSERT INTO team_images (id, team_id, user_id) VALUES ($1, $2, $3)", image, id, user)
	return err
}

func (p pgTeams) image(ctx context.Context, id int64, image string) (found, original bool, err error) {
	err = p.db.QueryRow(ctx, "SELECT original FROM team_images WHERE id = $1 AND team_id = $2", image, id).Scan(&original)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, false, nil
	}
	return err == nil, original, err
}

func (p pgTeams) pruneImages(ctx context.Context, t time.Time) ([]string, error) {
	rows, _ := p.db.Query(ctx, `UPDATE team_images i SET original = false FROM teams t
		WHERE i.team_id = t.id AND i.original AND t.ended_at < $1 RETURNING i.id`, t)
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

var errNoImage = errors.New("no such image")

// messageOf is what req's kind carries, or ok false if it lacks something or is out of range.
func messageOf(req *api.MessageRequest) (m api.Message, ok bool) {
	m.Kind = req.Kind
	at := req.Lat != nil && req.Lon != nil && math.Abs(*req.Lat) <= 90 && math.Abs(*req.Lon) <= 180
	switch req.Kind {
	case api.MessageKindText:
		m.Text = req.Text
		return m, req.Text != nil && strings.TrimSpace(*req.Text) != "" && utf8.RuneCountInString(*req.Text) <= 1000
	case api.MessageKindLocation:
		m.Lat, m.Lon = req.Lat, req.Lon
		return m, at
	case api.MessageKindImage:
		m.Image = req.Image
		return m, req.Image != nil
	case api.MessageKindSos: // with whatever the phone has
		m.Lat, m.Lon, m.Battery = req.Lat, req.Lon, req.Battery
		return m, (at || req.Lat == nil && req.Lon == nil) && (req.Battery == nil || *req.Battery >= 0 && *req.Battery <= 100)
	}
	return m, false
}

func (s *server) PostTeamMessage(ctx context.Context, req api.PostTeamMessageRequestObject) (api.PostTeamMessageResponseObject, error) {
	m, ok := messageOf(req.Body)
	if !ok {
		return api.PostTeamMessage400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u, store := userOf(ctx).id, s.teams.store
	err := s.teams.change(ctx, req.Id, u, func(_ api.Team, me api.Member) error {
		if m.Image != nil {
			found, _, err := store.image(ctx, req.Id, *m.Image)
			if err != nil {
				return err
			}
			if !found {
				return errNoImage
			}
		}
		m.Name, m.Time = me.Name, time.Now().Unix()
		var err error
		m, err = store.addMessage(ctx, req.Id, u, m)
		return err
	})
	switch {
	case errors.Is(err, errNotMember):
		return api.PostTeamMessage404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	case errors.Is(err, errNoImage):
		return api.PostTeamMessage400JSONResponse{Error: api.ErrorCodeImageNotFound}, nil
	case err != nil:
		return nil, err
	}
	return api.PostTeamMessage200JSONResponse(m), nil
}

// Photos are at most maxImageSide a side (the app sends 1600; this bounds the decode to 16 MB) and get a thumbnail thumbSide on the long side.
const (
	maxImageSide = 2048
	thumbSide    = 320
)

func (s *server) PostTeamImage(ctx context.Context, req api.PostTeamImageRequestObject) (api.PostTeamImageResponseObject, error) {
	u := userOf(ctx).id
	if _, ok, err := s.teams.member(ctx, req.Id, u, math.MaxInt64); err != nil {
		return nil, err
	} else if !ok {
		return api.PostTeamImage404JSONResponse{TeamNotFoundJSONResponse: teamNotFound}, nil
	}
	// ponytail: no per-team quota; the rate limit and 1 MB body cap bound it. Add one if disks fill.
	b, err := io.ReadAll(req.Body) // capped at 1 MB by withMiddleware
	if err != nil {
		return api.PostTeamImage400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	// The size first, so a small file claiming a huge image isn't decoded.
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil || format != "jpeg" || cfg.Width > maxImageSide || cfg.Height > maxImageSide {
		return api.PostTeamImage400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	img, err := jpeg.Decode(bytes.NewReader(b))
	if err != nil {
		return api.PostTeamImage400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	var thumb bytes.Buffer
	if err := jpeg.Encode(&thumb, thumbnail(img, thumbSide), &jpeg.Options{Quality: 80}); err != nil {
		return nil, err
	}
	var raw [16]byte
	rand.Read(raw[:])
	id := hex.EncodeToString(raw[:])
	orig, small := s.teams.imagePaths(id)
	if err := os.WriteFile(small, thumb.Bytes(), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(orig, b, 0o644); err != nil {
		return nil, err
	}
	if err := s.teams.store.addImage(ctx, req.Id, u, id); err != nil {
		os.Remove(orig)
		os.Remove(small)
		return nil, err
	}
	return api.PostTeamImage200JSONResponse{Image: id}, nil
}

var imageID = regexp.MustCompile(`^[0-9a-f]{32}$`)

func (s *server) GetTeamImage(ctx context.Context, req api.GetTeamImageRequestObject) (api.GetTeamImageResponseObject, error) {
	if _, ok, err := s.teams.member(ctx, req.Id, userOf(ctx).id, math.MaxInt64); err != nil {
		return nil, err
	} else if !ok {
		return api.GetTeamImage404JSONResponse{Error: api.ErrorCodeTeamNotFound}, nil
	}
	notFound := api.GetTeamImage404JSONResponse{Error: api.ErrorCodeImageNotFound}
	if !imageID.MatchString(req.Image) { // it becomes a file name
		return notFound, nil
	}
	found, original, err := s.teams.store.image(ctx, req.Id, req.Image)
	if err != nil {
		return nil, err
	} else if !found {
		return notFound, nil
	}
	file, small := s.teams.imagePaths(req.Image)
	if !original || req.Params.Thumb != nil && *req.Params.Thumb {
		file = small
	}
	b, err := os.ReadFile(file)
	if err != nil {
		return nil, err
	}
	cache := "private, max-age=31536000, immutable"
	return api.GetTeamImage200ImagejpegResponse{Body: bytes.NewReader(b), ContentLength: int64(len(b)), Headers: api.GetTeamImage200ResponseHeaders{CacheControl: &cache}}, nil
}

func (tm *teams) imagePaths(id string) (original, thumb string) {
	return filepath.Join(tm.images, id+".jpg"), filepath.Join(tm.images, id+".thumb.jpg")
}

// pruneImages deletes the originals of teams ended before t (§3.2: 180 days after 结束行程).
func (tm *teams) pruneImages(ctx context.Context, t time.Time) error {
	ids, err := tm.store.pruneImages(ctx, t)
	for _, id := range ids {
		orig, _ := tm.imagePaths(id)
		if err := os.Remove(orig); err != nil && !os.IsNotExist(err) {
			log.Printf("prune %s: %v", id, err)
		}
	}
	return err
}

// thumbnail is img scaled down to side on its long side (not up), each pixel the mean of those it covers.
func thumbnail(img image.Image, side int) image.Image {
	b := img.Bounds()
	sw, sh := b.Dx(), b.Dy()
	w, h := sw, sh
	if long := max(sw, sh); long > side {
		w, h = max(1, sw*side/long), max(1, sh*side/long)
	}
	out := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		y0, y1 := y*sh/h, max((y+1)*sh/h, y*sh/h+1)
		for x := range w {
			x0, x1 := x*sw/w, max((x+1)*sw/w, x*sw/w+1)
			var r, g, bl, n uint64
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					cr, cg, cb, _ := img.At(b.Min.X+sx, b.Min.Y+sy).RGBA()
					r, g, bl, n = r+uint64(cr), g+uint64(cg), bl+uint64(cb), n+1
				}
			}
			out.Set(x, y, color.RGBA64{uint16(r / n), uint16(g / n), uint16(bl / n), 0xffff})
		}
	}
	return out
}
