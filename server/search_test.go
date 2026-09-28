package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strings"
	"testing"

	"stars-outdoor/server/api"
)

// fakeSearch stands in for Photon and 天地图: Photon answers with photon (a FeatureCollection), or 502
// when it's empty; 天地图 answers with one POI for the right key and an error status otherwise.
type fakeSearch struct {
	photon      string
	photonHits  int
	photonQuery url.Values
	tdtHits     int
	tdtQuery    url.Values
}

func (f *fakeSearch) handler(t *testing.T) http.Handler {
	serve := func(h http.HandlerFunc) string {
		s := httptest.NewServer(h)
		t.Cleanup(s.Close)
		return s.URL
	}
	h := search{
		photon: serve(func(w http.ResponseWriter, r *http.Request) {
			f.photonHits++
			f.photonQuery = r.URL.Query()
			if r.URL.Path != "/api" || f.photon == "" {
				w.WriteHeader(502)
				return
			}
			w.Write([]byte(f.photon))
		}),
		tianditu: serve(func(w http.ResponseWriter, r *http.Request) {
			f.tdtHits++
			f.tdtQuery = r.URL.Query()
			if r.URL.Path != "/v2/search" || r.URL.Query().Get("tk") != "KEY" {
				w.Write([]byte(`{"status":{"infocode":1001,"cndesc":"权限类型错误"}}`))
				return
			}
			w.Write([]byte(`{"resultType":1,"count":1,"pois":[{"name":"太白山国家森林公园","address":"陕西省宝鸡市眉县","lonlat":"107.6,34.05"}],"status":{"infocode":1000}}`))
		}),
		key: "KEY", client: http.DefaultClient,
	}
	return withMiddleware(routes(1, okDB, nil, nil, nil, &h, nil, nil), 1, 1000)
}

const photonPeak = `{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"osm_key":"natural","osm_value":"peak","name":"拔仙台","city":"宝鸡市","state":"陕西省","country":"中国"},"geometry":{"type":"Point","coordinates":[107.7652754,33.9551193]}}]}`

func searchOf(t *testing.T, w *httptest.ResponseRecorder) api.SearchResults {
	t.Helper()
	var res api.SearchResults
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &res) != nil {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	return res
}

func TestSearchAsksPhotonNearTheMapCentre(t *testing.T) {
	f := &fakeSearch{photon: photonPeak}
	res := searchOf(t, get(f.handler(t), "/v1/search?q=%E6%8B%94%E4%BB%99%E5%8F%B0&lat=45&lon=10"))
	if len(res.Places) != 1 || !reflect.DeepEqual(res.Places[0], api.Place{Name: "拔仙台", Kind: "peak", Lon: 107.7652754, Lat: 33.9551193, Detail: ptr("陕西省 宝鸡市")}) {
		t.Fatalf("%+v", res.Places)
	}
	if q := f.photonQuery; q.Get("q") != "拔仙台" || q.Get("lat") != "45" || q.Get("lon") != "10" {
		t.Errorf("photon got %v", q)
	}
	// Few results, but the centre (Italy) is outside China: no 天地图.
	if f.tdtHits != 0 || len(res.Sources) != 1 || res.Sources[0] != api.Photon {
		t.Errorf("tianditu hits %d, sources %v", f.tdtHits, res.Sources)
	}
}

func TestFewResultsInChinaAddTianditu(t *testing.T) {
	f := &fakeSearch{photon: photonPeak}
	res := searchOf(t, get(f.handler(t), "/v1/search?q=%E5%A4%AA%E7%99%BD%E5%B1%B1&lat=34&lon=107.8"))
	if len(res.Places) != 2 || !reflect.DeepEqual(res.Places[1], api.Place{Name: "太白山国家森林公园", Kind: "poi", Lon: 107.6, Lat: 34.05, Detail: ptr("陕西省宝鸡市眉县")}) {
		t.Fatalf("%+v", res.Places)
	}
	var post struct{ KeyWord string }
	if json.Unmarshal([]byte(f.tdtQuery.Get("postStr")), &post); post.KeyWord != "太白山" {
		t.Errorf("tianditu got %v", f.tdtQuery)
	}
	if len(res.Sources) != 2 {
		t.Errorf("sources %v", res.Sources)
	}
}

func TestSearchIsCachedForTheSameQueryAndPlace(t *testing.T) {
	f := &fakeSearch{photon: photonPeak}
	h := f.handler(t)
	for _, p := range []string{"/v1/search?q=a&lat=34.001&lon=107.8", "/v1/search?q=a&lat=34.002&lon=107.8"} {
		searchOf(t, get(h, p))
	}
	if f.photonHits != 1 {
		t.Errorf("photon asked %d times", f.photonHits)
	}
	searchOf(t, get(h, "/v1/search?q=b&lat=34&lon=107.8"))
	if f.photonHits != 2 {
		t.Errorf("new query: photon asked %d times", f.photonHits)
	}
}

func TestPhotonDownFallsBackToTianditu(t *testing.T) {
	f := &fakeSearch{}
	res := searchOf(t, get(f.handler(t), "/v1/search?q=a&lat=34&lon=107.8"))
	if len(res.Places) != 1 || len(res.Sources) != 1 || res.Sources[0] != api.Tianditu {
		t.Fatalf("%+v", res)
	}
}

func TestEveryProviderDownIsDataUnavailable(t *testing.T) {
	f := &fakeSearch{}
	w := get(f.handler(t), "/v1/search?q=a&lat=45&lon=10")
	if w.Code != 503 || errorOf(w) != "data_unavailable" {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestBadSearchIsInvalid(t *testing.T) {
	f := &fakeSearch{photon: photonPeak}
	h := f.handler(t)
	for _, p := range []string{"/v1/search", "/v1/search?q=", "/v1/search?q=%20", "/v1/search?q=" + strings.Repeat("a", 101), "/v1/search?q=a&lat=91&lon=0", "/v1/search?q=a&lat=x"} {
		if w := get(h, p); w.Code != 400 || errorOf(w) != "invalid_request" {
			t.Errorf("%s: %d %s", p, w.Code, w.Body)
		}
	}
	if f.photonHits != 0 {
		t.Errorf("photon asked %d times", f.photonHits)
	}
}
