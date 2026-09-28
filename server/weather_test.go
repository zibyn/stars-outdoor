package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"stars-outdoor/server/api"
)

// 10:00 and 11:00 Beijing time on 28 Sep 2026.
var h10 = time.Date(2026, 9, 28, 10, 0, 0, 0, time.FixedZone("CST", 8*3600)).Unix()

// fakeQWeather answers like 和风 for a JWT signed by pub: two hours of forecast (the second a
// 雷阵雨 in a strong wind) and a 雷电 warning; everything else is 401.
func fakeQWeather(t *testing.T, pub ed25519.PublicKey, calls *atomic.Int32) *httptest.Server {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		parts := strings.Split(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), ".")
		sig, _ := base64.RawURLEncoding.DecodeString(parts[len(parts)-1])
		head, _ := base64.RawURLEncoding.DecodeString(parts[0])
		if len(parts) != 3 || !ed25519.Verify(pub, []byte(parts[0]+"."+parts[1]), sig) || !strings.Contains(string(head), `"kid":"KID"`) {
			w.WriteHeader(401)
			return
		}
		switch {
		case r.URL.Path == "/v7/weather/168h" && r.URL.Query().Get("location") == "107.77,33.96":
			fmt.Fprint(w, `{"code":"200","hourly":[
				{"fxTime":"2026-09-28T10:00+08:00","temp":"2","icon":"101","windSpeed":"20","precip":"0.0"},
				{"fxTime":"2026-09-28T11:00+08:00","temp":"1","icon":"302","windSpeed":"50","precip":"9.5"}]}`)
		case r.URL.Path == "/weatheralert/v1/current/33.96/107.77":
			fmt.Fprint(w, `{"alerts":[{"id":"a1","headline":"周至县发布雷电黄色预警","description":"预计未来6小时有雷电活动","eventType":{"name":"雷电"}}]}`)
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(up.Close)
	return up
}

// fakeOpenMeteo gives a forecast with gusts, 体感温度 and the ground elevation; 11:00 lacks a temperature.
func fakeOpenMeteo(t *testing.T, calls *atomic.Int32) *httptest.Server {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch r.URL.Path {
		case "/v1/forecast":
			fmt.Fprintf(w, `{"elevation":1480.0,"hourly":{"time":[%d,%d],"temperature_2m":[5.5,null],"apparent_temperature":[3.1,0],
				"precipitation":[0.2,0],"wind_gusts_10m":[18.0,0],"weather_code":[95,0]}}`, h10, h10+3600)
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(up.Close)
	return up
}

func newQWeather(t *testing.T) (*qweather, ed25519.PublicKey) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return &qweather{projectID: "PROJ", keyID: "KID", key: key}, pub
}

func weatherHandler(wx *weather, perMin int) http.Handler {
	return withMiddleware(routes(1, okDB, nil, nil, wx, nil, nil, nil), 1, perMin)
}

func postWeather(h http.Handler, body, device string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/v1/weather", strings.NewReader(body))
	r.Header.Set("X-Device-Id", device)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func points(ps ...[3]float64) string {
	var b []string
	for _, p := range ps {
		b = append(b, fmt.Sprintf(`{"lon":%g,"lat":%g,"time":%d}`, p[0], p[1], int64(p[2])))
	}
	return `{"points":[` + strings.Join(b, ",") + `]}`
}

func decode(t *testing.T, w *httptest.ResponseRecorder) api.Weather {
	t.Helper()
	if w.Code != 200 {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	var res api.Weather
	if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
		t.Fatal(err)
	}
	return res
}

func TestWeatherFromQWeatherAtEachPointsHour(t *testing.T) {
	var qc, oc atomic.Int32
	q, pub := newQWeather(t)
	q.base = fakeQWeather(t, pub, &qc).URL
	wx := newWeather(q, fakeOpenMeteo(t, &oc).URL, http.DefaultClient, 1000)
	// Two points in the same 0.01° cell, arriving 10:40 and 11:05; a third past the forecast.
	res := decode(t, postWeather(weatherHandler(wx, 100), points(
		[3]float64{107.7712, 33.9601, float64(h10 + 40*60)},
		[3]float64{107.7698, 33.9649, float64(h10 + 65*60)},
		[3]float64{107.77, 33.96, float64(h10 + 5*3600)},
	), ""))
	if len(res.Hours) != 2 || res.Hours[0].Point != 0 || res.Hours[1].Point != 1 {
		t.Fatalf("hours %+v", res.Hours)
	}
	a, b := res.Hours[0], res.Hours[1]
	// Gusts and the ground elevation from Open-Meteo.
	if a.Temp != 2 || a.Precip != 0 || a.Thunder || a.Gust != 18 || a.Elevation == nil || *a.Elevation != 1480 {
		t.Errorf("10:00 %+v", a)
	}
	// 雷阵雨 (icon 302); no gust from Open-Meteo: 50 km/h mean wind ≈ 13.9 m/s, gusts half as much again; wind chill below 1°C.
	if b.Temp != 1 || b.Precip != 9.5 || !b.Thunder || b.Gust < 17.2 || b.Gust > 25 || b.FeelsLike >= -3 {
		t.Errorf("11:00 %+v", b)
	}
	if len(res.Warnings) != 1 || res.Warnings[0].Id != "a1" || !res.Warnings[0].Thunder || !strings.Contains(res.Warnings[0].Title, "雷电") {
		t.Errorf("warnings %+v", res.Warnings)
	}
	if len(res.Sources) != 1 || res.Sources[0] != "qweather" {
		t.Errorf("sources %v", res.Sources)
	}
	// One cell: 和风's forecast and warnings, Open-Meteo's forecast; the second ask is cached.
	if qc.Load() != 2 || oc.Load() != 1 {
		t.Errorf("upstream calls: qweather %d, open-meteo %d", qc.Load(), oc.Load())
	}
	decode(t, postWeather(weatherHandler(wx, 100), points([3]float64{107.77, 33.96, float64(h10)}), ""))
	if qc.Load() != 2 || oc.Load() != 1 {
		t.Errorf("not cached: qweather %d, open-meteo %d", qc.Load(), oc.Load())
	}
}

func TestWeatherFallsBackToOpenMeteo(t *testing.T) {
	var qc, oc atomic.Int32
	q, _ := newQWeather(t)
	other, _, _ := ed25519.GenerateKey(rand.Reader) // 和风 refuses our token
	q.base = fakeQWeather(t, other, &qc).URL
	om := fakeOpenMeteo(t, &oc).URL
	for name, q := range map[string]*qweather{"refused": q, "not configured": nil} {
		res := decode(t, postWeather(weatherHandler(newWeather(q, om, http.DefaultClient, 1000), 100), points([3]float64{107.77, 33.96, float64(h10 + 59*60)}, [3]float64{107.77, 33.96, float64(h10 + 3600)}), ""))
		// 11:00 has no temperature: that hour is left out.
		if len(res.Hours) != 1 || len(res.Sources) != 1 || res.Sources[0] != "open-meteo" {
			t.Fatalf("%s: %+v", name, res)
		}
		h := res.Hours[0]
		if h.Temp != 5.5 || h.FeelsLike != 3.1 || h.Gust != 18 || !h.Thunder || h.Elevation == nil || *h.Elevation != 1480 {
			t.Errorf("%s: %+v", name, h)
		}
	}
}

func TestWeatherWithNoProviderIsDataUnavailable(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(502) }))
	defer down.Close()
	for _, wx := range []*weather{nil, newWeather(nil, down.URL, http.DefaultClient, 1000)} {
		if w := postWeather(weatherHandler(wx, 100), points([3]float64{107.77, 33.96, float64(h10)}), ""); w.Code != 503 || !strings.Contains(w.Body.String(), "data_unavailable") {
			t.Errorf("%d %s", w.Code, w.Body)
		}
	}
}

func TestWeatherRejectsBadPoints(t *testing.T) {
	var oc atomic.Int32
	h := weatherHandler(newWeather(nil, fakeOpenMeteo(t, &oc).URL, http.DefaultClient, 1000), 1000)
	many := make([][3]float64, 101)
	for i := range many {
		many[i] = [3]float64{107.77, 33.96, float64(h10)}
	}
	for _, body := range []string{`{"points":[]}`, `{}`, points([3]float64{107.77, 91, 0}), points([3]float64{181, 33.96, 0}), points(many...), `nope`} {
		if w := postWeather(h, body, ""); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_request") {
			t.Errorf("%.40s: %d %s", body, w.Code, w.Body)
		}
	}
	if oc.Load() != 0 {
		t.Errorf("fetched for a bad request")
	}
}

func TestWeatherCellsPerDeviceAreCapped(t *testing.T) {
	var oc atomic.Int32
	h := weatherHandler(newWeather(nil, fakeOpenMeteo(t, &oc).URL, http.DefaultClient, 3), 1000)
	// Two cells, then two more: past the 3-cell quota.
	two := points([3]float64{107.77, 33.96, float64(h10)}, [3]float64{107.78, 33.96, float64(h10)})
	if w := postWeather(h, two, "d"); w.Code != 200 {
		t.Fatalf("first: %d", w.Code)
	}
	if w := postWeather(h, two, "d"); w.Code != 429 || !strings.Contains(w.Body.String(), `"error":"daily_quota_exceeded","quotaCells":3`) {
		t.Fatalf("second: %d %s", w.Code, w.Body)
	}
	if w := postWeather(h, two, "e"); w.Code != 200 {
		t.Fatalf("other device: %d", w.Code)
	}
}

func TestQWeatherKeyLoadsFromPKCS8PEM(t *testing.T) {
	if q, err := loadQWeather("", "", "", "/nonexistent"); q != nil || err != nil {
		t.Fatalf("unconfigured: %v %v", q, err)
	}
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	path := filepath.Join(t.TempDir(), "ed25519-private.pem")
	os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600)
	q, err := loadQWeather("abc.re.qweatherapi.com", "PROJ", "KID", path)
	if err != nil || q.base != "https://abc.re.qweatherapi.com" {
		t.Fatalf("%v %+v", err, q)
	}
	parts := strings.Split(q.token(time.Now()), ".")
	sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
	claims, _ := base64.RawURLEncoding.DecodeString(parts[1])
	if !ed25519.Verify(pub, []byte(parts[0]+"."+parts[1]), sig) || !strings.Contains(string(claims), `"sub":"PROJ"`) {
		t.Fatalf("token %s", claims)
	}
	if _, err := loadQWeather("h", "", "", filepath.Join(t.TempDir(), "missing.pem")); err == nil {
		t.Fatal("missing key file loaded")
	}
}
