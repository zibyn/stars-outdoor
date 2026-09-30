package main

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"stars-outdoor/server/api"
)

type memMessage struct {
	team int64
	m    api.Message
}

type memImage struct {
	team     int64
	original bool
}

func (m *memTeams) addMessage(_ context.Context, id, user int64, msg api.Message) (api.Message, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.seq++
	msg.Seq, msg.From = m.seq, &user
	m.messages = append(m.messages, memMessage{id, msg})
	return msg, nil
}

func (m *memTeams) addImage(_ context.Context, id, _ int64, image string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.images == nil {
		m.images = map[string]*memImage{}
	}
	m.images[image] = &memImage{team: id, original: true}
	return nil
}

func (m *memTeams) image(_ context.Context, id int64, image string) (found, original bool, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	im := m.images[image]
	if im == nil || im.team != id {
		return false, false, nil
	}
	return true, im.original, nil
}

// ponytail: memTeams keeps no end time; every ended team counts as ended before t.
func (m *memTeams) pruneImages(_ context.Context, _ time.Time) (ids []string, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, im := range m.images {
		if im.original && m.teams[im.team-1].ended {
			im.original = false
			ids = append(ids, id)
		}
	}
	return ids, nil
}

func TestChatMessagesAfterCursor(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`","name":"老王"}`)
	w := do(h, "POST", path(tm, "/messages"), b, `{"kind":"text","text":"到垭口了"}`)
	var sent api.Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &sent) != nil || sent.Name != "老王" || *sent.From != 2 || *sent.Text != "到垭口了" || sent.Time == 0 {
		t.Fatalf("text: %d %s", w.Code, w.Body)
	}
	got := teamOf(t, do(h, "GET", path(tm, ""), a, ""))
	if len(got.Messages) != 1 || got.Messages[0].Seq != sent.Seq || got.Cursor < sent.Seq {
		t.Fatalf("messages: %+v", got)
	}
	// A kind keeps only what it carries.
	w = do(h, "POST", path(tm, "/messages"), a, `{"kind":"location","lat":34.1,"lon":108.2,"text":"x"}`)
	var loc api.Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &loc) != nil || *loc.Lat != 34.1 || loc.Text != nil {
		t.Fatalf("location: %d %s", w.Code, w.Body)
	}
	after := teamOf(t, do(h, "GET", path(tm, "?after="+strconv.FormatInt(got.Cursor, 10)), b, ""))
	if len(after.Messages) != 1 || after.Messages[0].Kind != api.MessageKindLocation {
		t.Fatalf("after cursor: %+v", after.Messages)
	}
	// The sender's 沿轨里程 on the 队伍轨迹 goes with a location: all of it, none (off it), or nothing (no 队伍轨迹).
	w = do(h, "POST", path(tm, "/messages"), a, `{"kind":"location","lat":34.1,"lon":108.2,"along":[3100,13700]}`)
	var on api.Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &on) != nil || len(*on.Along) != 2 || (*on.Along)[1] != 13700 {
		t.Fatalf("along: %d %s", w.Code, w.Body)
	}
	w = do(h, "POST", path(tm, "/messages"), a, `{"kind":"location","lat":34.1,"lon":108.2,"along":[]}`)
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &on) != nil || on.Along == nil || len(*on.Along) != 0 {
		t.Fatalf("off the track: %d %s", w.Code, w.Body)
	}
	if loc.Along != nil {
		t.Fatalf("no 队伍轨迹: %+v", loc.Along)
	}
	for _, body := range []string{`{"kind":"text"}`, `{"kind":"text","text":"  "}`, `{"kind":"text","text":"` + strings.Repeat("字", 1001) + `"}`,
		`{"kind":"location","lat":34}`, `{"kind":"location","lat":91,"lon":0}`, `{"kind":"sos","lat":34,"lon":108}`,
		`{"kind":"image"}`, `{"kind":"shout","text":"x"}`, `{"kind":"location","lat":34,"lon":108,"along":[-1]}`} {
		if w := do(h, "POST", path(tm, "/messages"), b, body); w.Code != 400 {
			t.Fatalf("%s: %d %s", body, w.Code, w.Body)
		}
	}
	if w := do(h, "POST", path(tm, "/messages"), b, `{"kind":"image","image":"00000000000000000000000000000000"}`); w.Code != 400 || !strings.Contains(w.Body.String(), "image_not_found") {
		t.Fatalf("unknown image: %d %s", w.Code, w.Body)
	}
	// The 对话 stays after 结束行程, not after 退出队伍.
	do(h, "POST", path(tm, "/end"), a, "")
	if w := do(h, "POST", path(tm, "/messages"), b, `{"kind":"text","text":"到家了"}`); w.Code != 200 {
		t.Fatalf("after end: %d %s", w.Code, w.Body)
	}
	do(h, "POST", path(tm, "/leave"), b, "")
	if w := do(h, "POST", path(tm, "/messages"), b, `{"kind":"text","text":"x"}`); w.Code != 404 {
		t.Fatalf("after leave: %d", w.Code)
	}
	if got := teamOf(t, do(h, "GET", path(tm, ""), a, "")); len(got.Messages) != 5 || got.Messages[4].Name != "老王" {
		t.Fatalf("left member's messages stay: %+v", got.Messages)
	}
}

func TestMessagesGoOutLive(t *testing.T) {
	h, _ := teamServer(t)
	srv := httptest.NewServer(h)
	defer srv.Close()
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	c := dial(t, srv, path(tm, "/live"), a)
	next(t, c)
	do(h, "POST", path(tm, "/messages"), b, `{"kind":"location","lat":34,"lon":108}`)
	ev := next(t, c)
	if len(ev.Messages) != 1 || ev.Messages[0].Kind != api.MessageKindLocation || *ev.Messages[0].Lat != 34 || *ev.Messages[0].From != 2 {
		t.Fatalf("message event: %+v", ev.Messages)
	}
}

func testJPEG(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.Set(x, y, color.RGBA{uint8(x), uint8(y), 128, 255})
		}
	}
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, nil); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func TestImagesThumbnailAndPrune(t *testing.T) {
	h, teams := teamServer(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	photo := testJPEG(t, 1600, 800)
	w := do(h, "POST", path(tm, "/images"), b, string(photo))
	var up api.Image
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &up) != nil || up.Image == "" {
		t.Fatalf("upload: %d %s", w.Code, w.Body)
	}
	if w := do(h, "POST", path(tm, "/messages"), b, `{"kind":"image","image":"`+up.Image+`"}`); w.Code != 200 {
		t.Fatalf("image message: %d %s", w.Code, w.Body)
	}
	if w := do(h, "GET", path(tm, "/images/"+up.Image), a, ""); w.Code != 200 || !bytes.Equal(w.Body.Bytes(), photo) {
		t.Fatalf("original: %d", w.Code)
	}
	size := func(thumb string) image.Point {
		w := do(h, "GET", path(tm, "/images/"+up.Image+thumb), a, "")
		img, err := jpeg.Decode(w.Body)
		if w.Code != 200 || err != nil {
			t.Fatalf("image%s: %d %v", thumb, w.Code, err)
		}
		return img.Bounds().Size()
	}
	if s := size("?thumb=true"); s != (image.Point{320, 160}) {
		t.Fatalf("thumbnail: %v", s)
	}
	for _, body := range []string{"not a jpeg", string(testJPEG(t, 2049, 10))} {
		if w := do(h, "POST", path(tm, "/images"), b, body); w.Code != 400 {
			t.Fatalf("bad upload: %d %s", w.Code, w.Body)
		}
	}
	for _, p := range []string{"/images/..%2f..%2fetc%2fpasswd", "/images/00000000000000000000000000000000"} {
		if w := do(h, "GET", path(tm, p), a, ""); w.Code != 404 {
			t.Fatalf("%s: %d", p, w.Code)
		}
	}
	c := login(t, h, "13700137000")
	other := teamOf(t, do(h, "POST", "/v1/teams", c, `{}`))
	if w := do(h, "GET", path(tm, "/images/"+up.Image), c, ""); w.Code != 404 {
		t.Fatalf("stranger: %d", w.Code)
	}
	if w := do(h, "POST", path(other, "/messages"), c, `{"kind":"image","image":"`+up.Image+`"}`); w.Code != 400 {
		t.Fatalf("another team's image: %d", w.Code)
	}
	// Still running: pruning keeps it. Once ended (180 days ago, for the test): only the thumbnail.
	if err := teams.pruneImages(context.Background(), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if s := size(""); s.X != 1600 {
		t.Fatalf("pruned while running: %v", s)
	}
	do(h, "POST", path(tm, "/end"), a, "")
	if err := teams.pruneImages(context.Background(), time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if s := size(""); s.X != 320 {
		t.Fatalf("after prune: %v", s)
	}
	if _, err := os.Stat(filepath.Join(teams.images, up.Image+".jpg")); !os.IsNotExist(err) {
		t.Fatal("original still on disk")
	}
}
