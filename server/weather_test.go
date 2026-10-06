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
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"stars-trail/server/api"
)

// 10:40 Beijing time on 28 Sep 2026: the forecast starts at the 10:00 hour.
var nowAt = time.Date(2026, 9, 28, 10, 40, 0, 0, time.FixedZone("CST", 8*3600))
var h10 = time.Date(2026, 9, 28, 10, 0, 0, 0, time.FixedZone("CST", 8*3600)).Unix()

// openMeteoFake answers /v1/forecast for two hours before the 10:00 hour and 200 after it (temperature
// is the hour's offset), and records the last query. fail makes every answer 502.
type openMeteoFake struct {
	*httptest.Server
	calls atomic.Int32
	fail  atomic.Bool
	mu    sync.Mutex
	query url.Values
}

func newOpenMeteo(t *testing.T) *openMeteoFake {
	f := &openMeteoFake{}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		if f.fail.Load() {
			w.WriteHeader(502)
			return
		}
		f.mu.Lock()
		f.query = r.URL.Query()
		f.mu.Unlock()
		if r.URL.Path != "/v1/forecast" {
			w.WriteHeader(404)
			return
		}
		const n = 203 // offsets -2 .. 200
		var times, temp, feels, precip, gust, code, dir strings.Builder
		for i := 0; i < n; i++ {
			if i > 0 {
				times.WriteByte(',')
				temp.WriteByte(',')
				feels.WriteByte(',')
				precip.WriteByte(',')
				gust.WriteByte(',')
				code.WriteByte(',')
				dir.WriteByte(',')
			}
			fmt.Fprintf(&times, "%d", h10+int64(i-2)*3600)
			fmt.Fprintf(&temp, "%g", float64(i))
			fmt.Fprintf(&feels, "%g", float64(i)-0.5)
			fmt.Fprintf(&precip, "%g", float64(i)/10)
			fmt.Fprintf(&gust, "%g", float64(i)+1)
			code.WriteString("3") // 阴
			dir.WriteString("180")
		}
		fmt.Fprintf(w, `{"elevation":1480.0,"hourly":{"time":[%s],"temperature_2m":[%s],"apparent_temperature":[%s],
			"precipitation":[%s],"wind_gusts_10m":[%s],"weather_code":[%s],"wind_direction_10m":[%s]}}`,
			times.String(), temp.String(), feels.String(), precip.String(), gust.String(), code.String(), dir.String())
	}))
	t.Cleanup(f.Server.Close)
	return f
}

func (f *openMeteoFake) lastQuery() url.Values {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.query
}

// qweatherFake answers 和风's weather alert endpoint for the cell 33.96/107.77 with one 雷电 warning,
// after checking the JWT's signature; anything else is 404. fail makes every answer 502.
type qweatherFake struct {
	*httptest.Server
	calls atomic.Int32
	fail  atomic.Bool
}

func newQWeatherFake(t *testing.T, pub ed25519.PublicKey) *qweatherFake {
	f := &qweatherFake{}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		if f.fail.Load() {
			w.WriteHeader(502)
			return
		}
		parts := strings.Split(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), ".")
		sig, _ := base64.RawURLEncoding.DecodeString(parts[len(parts)-1])
		head, _ := base64.RawURLEncoding.DecodeString(parts[0])
		if len(parts) != 3 || !ed25519.Verify(pub, []byte(parts[0]+"."+parts[1]), sig) || !strings.Contains(string(head), `"kid":"KID"`) {
			w.WriteHeader(401)
			return
		}
		switch r.URL.Path {
		case "/weatheralert/v1/current/33.96/107.77":
			fmt.Fprint(w, `{"alerts":[{"id":"a1","headline":"周至县发布雷电黄色预警","description":"预计未来6小时有雷电活动",
				"senderName":"西安市气象台","issuedTime":"2026-09-28T09:30+08:00","eventType":{"name":"雷电"}}]}`)
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(f.Server.Close)
	return f
}

// newQWeather is a 和风 client pointed at a fake, and the public key that fake verifies tokens with.
func newQWeather(t *testing.T) (*qweather, *qweatherFake) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	f := newQWeatherFake(t, pub)
	return &qweather{base: f.URL, projectID: "PROJ", keyID: "KID", key: key}, f
}

func weatherHandler(wx *weather) http.Handler {
	return withMiddleware(routes(1, okDB, nil, nil, wx, nil, nil, nil, nil), 1)
}

// newWeatherAt is a weather service whose clock stands at nowAt.
func newWeatherAt(q *qweather, openMeteo string, requestsPerDay int64) *weather {
	wx := newWeather(q, openMeteo, http.DefaultClient, requestsPerDay)
	wx.now = func() time.Time { return nowAt }
	return wx
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

func TestWeatherForecastAtPlaceElevation(t *testing.T) {
	f := newOpenMeteo(t)
	h := weatherHandler(newWeatherAt(nil, f.URL, 1000))
	res := decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1487", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk {
		t.Fatalf("forecast %q: %+v", res.Forecast, res)
	}
	// ele 1487 rounds to the 1500 m band, and the answer says the temperatures are for that height.
	if res.Elevation == nil || *res.Elevation != 1500 {
		t.Errorf("elevation %v", res.Elevation)
	}
	// A week from nowAt's 10:00 hour; the fake's earlier hours are dropped.
	if len(res.Hours) != weatherHours || res.Hours[0].Time != h10 || res.Hours[weatherHours-1].Time != h10+(weatherHours-1)*3600 {
		t.Fatalf("hours: %d, %d..%d", len(res.Hours), res.Hours[0].Time, res.Hours[len(res.Hours)-1].Time)
	}
	h0 := res.Hours[0]
	if h0.Temp != 2 || h0.FeelsLike != 1.5 || h0.Precip != 0.2 || h0.Gust != 3 || h0.Sky != api.Cloudy || h0.WindDir == nil || *h0.WindDir != 180 {
		t.Errorf("10:00 %+v", h0)
	}
	if len(res.Sources) != 1 || res.Sources[0] != api.OpenMeteo {
		t.Errorf("sources %v", res.Sources)
	}
	// 和风 isn't configured: no warnings, and the app is told so rather than shown "none".
	if len(res.Warnings) != 0 || res.WarningsFailed == nil || !*res.WarningsFailed {
		t.Errorf("warnings %+v failed %v", res.Warnings, res.WarningsFailed)
	}
	q := f.lastQuery()
	if q.Get("elevation") != "1500" {
		t.Errorf("elevation sent %q", q.Get("elevation"))
	}
	if q.Get("latitude") != "33.96" || q.Get("longitude") != "107.77" {
		t.Errorf("asked for %s,%s", q.Get("latitude"), q.Get("longitude"))
	}
	if q.Get("hourly") != "temperature_2m,apparent_temperature,precipitation,wind_gusts_10m,weather_code,wind_direction_10m" {
		t.Errorf("hourly %q", q.Get("hourly"))
	}
}

func TestWeatherWithoutElevationUsesOpenMeteoHeight(t *testing.T) {
	f := newOpenMeteo(t)
	h := weatherHandler(newWeatherAt(nil, f.URL, 1000))
	res := decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk {
		t.Fatalf("forecast %q: %+v", res.Forecast, res)
	}
	// Open-Meteo's 90 m DEM height at the place, not a band we chose.
	if res.Elevation == nil || *res.Elevation != 1480 {
		t.Errorf("elevation %v", res.Elevation)
	}
	if f.lastQuery().Has("elevation") {
		t.Errorf("elevation sent when none was given: %v", f.lastQuery())
	}
}

func TestWeatherCachesByCellAndElevationBand(t *testing.T) {
	f := newOpenMeteo(t)
	now := nowAt
	wx := newWeather(nil, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return now }
	h := weatherHandler(wx)
	// The same 0.01° cell (33.96, 107.77) and 1500 m band: 1487 and 1451 both round to it.
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1487", "X-Device-Id", "d"))
	decode(t, get(h, "/v1/weather?lat=33.9649&lon=107.7698&ele=1451", "X-Device-Id", "d"))
	if n := f.calls.Load(); n != 1 {
		t.Fatalf("same cell and band: %d Open-Meteo calls, want 1", n)
	}
	// A different band, and no elevation at all, are their own keys.
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1620", "X-Device-Id", "d"))
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712", "X-Device-Id", "d"))
	if n := f.calls.Load(); n != 3 {
		t.Fatalf("three keys: %d calls, want 3", n)
	}
	// Still cached two hours on; expired four hours on.
	now = nowAt.Add(2 * time.Hour)
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1487", "X-Device-Id", "d"))
	if n := f.calls.Load(); n != 3 {
		t.Fatalf("within the 3 h TTL: %d calls, want 3", n)
	}
	now = nowAt.Add(4 * time.Hour)
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1487", "X-Device-Id", "d"))
	if n := f.calls.Load(); n != 4 {
		t.Fatalf("after the 3 h TTL: %d calls, want 4", n)
	}
}

func TestWeatherCachesWarningsPerCell(t *testing.T) {
	f := newOpenMeteo(t)
	q, qf := newQWeather(t)
	wx := newWeatherAt(q, f.URL, 1000)
	h := weatherHandler(wx)
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1487", "X-Device-Id", "d"))
	decode(t, get(h, "/v1/weather?lat=33.9649&lon=107.7698&ele=100", "X-Device-Id", "d"))
	if n := qf.calls.Load(); n != 1 {
		t.Fatalf("%d 和风 calls for one cell, want 1", n)
	}
}

func TestWeatherWarningsHaveSenderAndIssuedAt(t *testing.T) {
	f := newOpenMeteo(t)
	q, _ := newQWeather(t)
	res := decode(t, get(weatherHandler(newWeatherAt(q, f.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if len(res.Warnings) != 1 {
		t.Fatalf("warnings %+v", res.Warnings)
	}
	a := res.Warnings[0]
	if a.Id != "a1" || !a.Thunder || !strings.Contains(a.Title, "雷电") {
		t.Errorf("warning %+v", a)
	}
	if a.Sender == nil || *a.Sender != "西安市气象台" {
		t.Errorf("sender %v", a.Sender)
	}
	issued := time.Date(2026, 9, 28, 9, 30, 0, 0, time.FixedZone("CST", 8*3600))
	if a.IssuedAt == nil || !a.IssuedAt.Equal(issued) {
		t.Errorf("issuedAt %v", a.IssuedAt)
	}
	if res.WarningsFailed != nil {
		t.Errorf("warningsFailed set though 和风 answered: %v", res.WarningsFailed)
	}
	if len(res.Sources) != 2 || res.Sources[0] != api.OpenMeteo || res.Sources[1] != api.Qweather {
		t.Errorf("sources %v", res.Sources)
	}
}

func TestWeatherOpenMeteoFails(t *testing.T) {
	f := newOpenMeteo(t)
	f.fail.Store(true)
	q, _ := newQWeather(t)
	res := decode(t, get(weatherHandler(newWeatherAt(q, f.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastFailed || len(res.Hours) != 0 || res.Elevation != nil {
		t.Errorf("forecast %q, hours %d, elevation %v", res.Forecast, len(res.Hours), res.Elevation)
	}
	if len(res.Warnings) != 1 || res.WarningsFailed != nil {
		t.Errorf("warnings %+v failed %v", res.Warnings, res.WarningsFailed)
	}
	if len(res.Sources) != 1 || res.Sources[0] != api.Qweather {
		t.Errorf("sources %v", res.Sources)
	}
}

func TestWeatherQWeatherFails(t *testing.T) {
	f := newOpenMeteo(t)
	q, qf := newQWeather(t)
	qf.fail.Store(true)
	for name, q := range map[string]*qweather{"refused": q, "not configured": nil} {
		res := decode(t, get(weatherHandler(newWeatherAt(q, f.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
		if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
			t.Fatalf("%s: forecast %q, hours %d", name, res.Forecast, len(res.Hours))
		}
		if len(res.Warnings) != 0 || res.WarningsFailed == nil || !*res.WarningsFailed {
			t.Errorf("%s: warnings %+v failed %v", name, res.Warnings, res.WarningsFailed)
		}
		if len(res.Sources) != 1 || res.Sources[0] != api.OpenMeteo {
			t.Errorf("%s: sources %v", name, res.Sources)
		}
	}
}

func TestWeatherOpenMeteoWithNoHours(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"elevation":1480.0,"hourly":{"time":[]}}`)
	}))
	defer up.Close()
	res := decode(t, get(weatherHandler(newWeatherAt(nil, up.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastFailed || len(res.Hours) != 0 {
		t.Errorf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
}

func TestWeatherBothFail(t *testing.T) {
	f := newOpenMeteo(t)
	f.fail.Store(true)
	q, qf := newQWeather(t)
	qf.fail.Store(true)
	res := decode(t, get(weatherHandler(newWeatherAt(q, f.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastFailed || len(res.Hours) != 0 {
		t.Errorf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	if len(res.Warnings) != 0 || res.WarningsFailed == nil || !*res.WarningsFailed {
		t.Errorf("warnings %+v failed %v", res.Warnings, res.WarningsFailed)
	}
	if len(res.Sources) != 0 {
		t.Errorf("sources %v", res.Sources)
	}
}

func TestWeatherWithNoServiceIsDataUnavailable(t *testing.T) {
	if w := get(weatherHandler(nil), "/v1/weather?lat=33.96&lon=107.77", "X-Device-Id", "d"); w.Code != 503 || !strings.Contains(w.Body.String(), "data_unavailable") {
		t.Errorf("%d %s", w.Code, w.Body)
	}
}

func TestWeatherRejectsBadCoordinates(t *testing.T) {
	f := newOpenMeteo(t)
	h := weatherHandler(newWeatherAt(nil, f.URL, 1000))
	for _, q := range []string{"lat=91&lon=107.77", "lat=33.96&lon=181", "lat=-91&lon=0"} {
		if w := get(h, "/v1/weather?"+q, "X-Device-Id", "d"); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_request") {
			t.Errorf("%s: %d %s", q, w.Code, w.Body)
		}
	}
	if f.calls.Load() != 0 {
		t.Errorf("fetched for a bad request")
	}
}

func TestWeatherQuotaIsPerRequest(t *testing.T) {
	f := newOpenMeteo(t)
	h := weatherHandler(newWeatherAt(nil, f.URL, 1))
	if w := get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"); w.Code != 200 {
		t.Fatalf("first: %d", w.Code)
	}
	// Charged even though the answer was cached.
	if w := get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"); w.Code != 429 ||
		!strings.Contains(w.Body.String(), `"error":"daily_quota_exceeded"`) || strings.Contains(w.Body.String(), "quotaCells") {
		t.Fatalf("second: %d %s", w.Code, w.Body)
	}
	if w := get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "e"); w.Code != 200 {
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
