package main

// 搜索 online (spec §2.10, issue #18): Photon (OSM) for everything, 天地图地名搜索 to fill in mainland
// places Photon lacks. The app ranks and adds its 山名别名表 and coordinates itself; offline it
// searches the places.sqlite in its offline packages instead.

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"stars-outdoor/server/api"
)

const (
	maxQueryLen = 100
	maxCached   = 10000
	// Fewer Photon places in China than this, searching near a point in China: ask 天地图 too (its quota
	// is small and unpublished).
	tiandituBelow = 5
)

// China's bbox, as scripts/build-data.sh cuts the offline data.
// ponytail: a bbox, so it also takes in the neighbours; a China outline if 天地图 answers there look wrong.
var chinaBbox = [4]float64{73.4, 18.0, 135.1, 53.6}

func inChina(lat, lon float64) bool {
	return lon >= chinaBbox[0] && lat >= chinaBbox[1] && lon <= chinaBbox[2] && lat <= chinaBbox[3]
}

type search struct {
	photon   string // "https://photon.komoot.io", self-hosted later (issue #18)
	tianditu string // "https://api.tianditu.gov.cn"
	key      string // TIANDITU_KEY; empty: Photon only
	client   *http.Client
	// Answers are cached for the clock hour, by query and centre to 0.1°.
	// ponytail: the whole map is dropped when the hour turns or it holds maxCached answers (queries are
	// typed, so unbounded otherwise); no singleflight, so two phones typing the same thing both ask
	// Photon. An LRU and singleflight (as weather.inflight) if Photon's fair use gets tight.
	mu    sync.Mutex
	hour  int64
	cache map[string]api.SearchResults
}

func (s *server) GetSearch(ctx context.Context, req api.GetSearchRequestObject) (api.GetSearchResponseObject, error) {
	p := req.Params
	q := strings.TrimSpace(p.Q)
	near := p.Lat != nil && p.Lon != nil
	if q == "" || utf8.RuneCountInString(q) > maxQueryLen || (p.Lat == nil) != (p.Lon == nil) || near && !(math.Abs(*p.Lat) <= 90 && math.Abs(*p.Lon) <= 180) {
		return api.GetSearch400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	unavailable := api.GetSearch503JSONResponse{DataUnavailableJSONResponse: api.DataUnavailableJSONResponse{Error: api.ErrorCodeDataUnavailable}}
	sr := s.search
	if sr == nil {
		return unavailable, nil
	}
	var lat, lon float64
	if near {
		lat, lon = math.Round(*p.Lat*10)/10, math.Round(*p.Lon*10)/10
	}
	key := fmt.Sprint(q, near, lat, lon)
	hour := time.Now().Unix() / 3600
	sr.mu.Lock()
	if hour != sr.hour || len(sr.cache) >= maxCached {
		sr.hour, sr.cache = hour, map[string]api.SearchResults{}
	}
	res, ok := sr.cache[key]
	sr.mu.Unlock()
	if ok {
		return api.GetSearch200JSONResponse(res), nil
	}

	res = api.SearchResults{Places: []api.Place{}, Sources: []api.SearchResultsSources{}}
	places, err := sr.fromPhoton(ctx, q, near, lat, lon)
	if err != nil {
		log.Printf("photon %q: %v", q, err)
	} else {
		res.Places = append(res.Places, places...)
		res.Sources = append(res.Sources, api.Photon)
	}
	mainland := 0
	for _, p := range res.Places {
		if inChina(p.Lat, p.Lon) {
			mainland++
		}
	}
	if mainland < tiandituBelow && near && inChina(lat, lon) && sr.key != "" {
		places, err := sr.fromTianditu(ctx, q)
		if err != nil {
			log.Printf("tianditu search %q: %s", q, redact(sr.key, err.Error()))
		} else {
			res.Places = append(res.Places, places...)
			res.Sources = append(res.Sources, api.Tianditu)
		}
	}
	if len(res.Sources) == 0 {
		return unavailable, nil
	}
	sr.mu.Lock()
	if sr.hour == hour {
		sr.cache[key] = res
	}
	sr.mu.Unlock()
	return api.GetSearch200JSONResponse(res), nil
}

func (sr *search) fromPhoton(ctx context.Context, q string, near bool, lat, lon float64) ([]api.Place, error) {
	v := url.Values{"q": {q}, "limit": {"15"}}
	if near {
		v.Set("lat", strconv.FormatFloat(lat, 'f', -1, 64))
		v.Set("lon", strconv.FormatFloat(lon, 'f', -1, 64))
	}
	var fc struct {
		Features []struct {
			Properties struct {
				Name                         string
				OsmValue                     string `json:"osm_value"`
				State, City, County, Country string
			}
			Geometry struct{ Coordinates []float64 }
		}
	}
	if err := getJSON(ctx, sr.client, sr.photon+"/api?"+v.Encode(), "", &fc); err != nil {
		return nil, err
	}
	places := []api.Place{}
	for _, f := range fc.Features {
		p, c := f.Properties, f.Geometry.Coordinates
		if p.Name == "" || len(c) != 2 {
			continue
		}
		var parts []string
		for _, s := range []string{p.State, p.City, p.County} {
			if s != "" && !strings.Contains(strings.Join(parts, " "), s) {
				parts = append(parts, s)
			}
		}
		if len(parts) == 0 && p.Country != "" {
			parts = []string{p.Country}
		}
		place := api.Place{Name: p.Name, Kind: p.OsmValue, Lon: c[0], Lat: c[1]}
		if len(parts) > 0 {
			place.Detail = ptr(strings.Join(parts, " "))
		}
		places = append(places, place)
	}
	return places, nil
}

// fromTianditu asks 地名搜索 V2.0 (普通搜索) over China; its CGCS2000 is WGS-84 to within centimetres.
func (sr *search) fromTianditu(ctx context.Context, q string) ([]api.Place, error) {
	post, _ := json.Marshal(map[string]any{
		"keyWord": q, "level": 12, "queryType": 1, "start": 0, "count": 10,
		"mapBound": fmt.Sprintf("%g,%g,%g,%g", chinaBbox[0], chinaBbox[1], chinaBbox[2], chinaBbox[3]),
	})
	var res struct {
		Pois []struct{ Name, Address, Lonlat string }
		Area *struct{ Name, Lonlat string }
		// 天地图 answers errors (bad key, quota) with 200 and a status.
		Status struct {
			Infocode int
			Cndesc   string
		}
	}
	u := sr.tianditu + "/v2/search?" + url.Values{"postStr": {string(post)}, "type": {"query"}, "tk": {sr.key}}.Encode()
	if err := getJSON(ctx, sr.client, u, "", &res); err != nil {
		return nil, err
	}
	if res.Status.Infocode != 1000 {
		return nil, fmt.Errorf("infocode %d %s", res.Status.Infocode, res.Status.Cndesc)
	}
	places := []api.Place{}
	add := func(name, kind, lonlat, detail string) {
		f := strings.FieldsFunc(lonlat, func(r rune) bool { return r == ',' || r == ' ' })
		if len(f) != 2 || name == "" {
			return
		}
		lon, err1 := strconv.ParseFloat(f[0], 64)
		lat, err2 := strconv.ParseFloat(f[1], 64)
		if err1 != nil || err2 != nil {
			return
		}
		p := api.Place{Name: name, Kind: kind, Lon: lon, Lat: lat}
		if detail != "" {
			p.Detail = &detail
		}
		places = append(places, p)
	}
	if res.Area != nil {
		add(res.Area.Name, "area", res.Area.Lonlat, "")
	}
	for _, p := range res.Pois {
		add(p.Name, "poi", p.Lonlat, p.Address)
	}
	return places, nil
}

func ptr[T any](v T) *T { return &v }
