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
	"slices"
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

// openMeteoFake answers /v1/forecast: the basic forecast for two hours before the 10:00 hour and 200
// after it (temperature is the hour's offset), and the 廓线 (#254) when the request carries the
// pressure-level variables; /v1/ensemble answers the ECMWF ensemble (#256). It records the last query
// of each kind. fail makes every answer 502; failProfile and failEnsemble only their own.
type openMeteoFake struct {
	*httptest.Server
	calls         atomic.Int32 // every request
	profileCalls  atomic.Int32 // the 廓线 requests
	ensembleCalls atomic.Int32 // the ensemble requests
	fail          atomic.Bool
	failProfile   atomic.Bool
	failEnsemble  atomic.Bool
	mu            sync.Mutex
	query         url.Values // the last basic request
	profileQuery  url.Values // the last 廓线 request
	ensembleQuery url.Values // the last ensemble request
	// basicElevation is the basic answer's elevation, the place's DEM height when no ele was given.
	basicElevation float64
	// profileJSON, when set, is the 廓线 answer as it stands, instead of the default profileForecast.
	profileJSON string
	// ensembleJSON, when set, is the ensemble answer as it stands, instead of the default ensembleForecast.
	ensembleJSON string
}

// profileForecast is the 廓线 answer: the grid ground at 3500 m, ten pressure levels at 1000 m steps
// (temperature 25-2j °C, humidity 40+5j %), the cloud layers, the 0°C level, and CAPE at 100 J/kg per
// hour (the hour's offset in the answer).
func profileForecast() string {
	rh := make([]float64, 10)
	for j := range rh {
		rh[j] = float64(40 + 5*j)
	}
	return profileAnswer(3500, rh, 40, 50, 0.1, 10)
}

// profileAnswer is a 廓线 answer for the given cell ground and per-level relative humidity (%): ten
// pressure levels at 1000 m steps (temperature 25-2j °C), given mid/high cloud (%) and precipitation
// (mm/h), wind_speed the same m/s at every level, and CAPE at 100 J/kg per hour.
func profileAnswer(ground float64, rh []float64, cloudMid, cloudHigh, precip, wind float64) string {
	const n = 203 // offsets -2 .. 200
	var b strings.Builder
	fmt.Fprintf(&b, `{"elevation":%g,"hourly":{"time":[`, ground)
	for i := 0; i < n; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, "%d", h10+int64(i-2)*3600)
	}
	b.WriteByte(']')
	series := func(name string, value func(i int) float64) {
		fmt.Fprintf(&b, `,"%s":[`, name)
		for i := 0; i < n; i++ {
			if i > 0 {
				b.WriteByte(',')
			}
			fmt.Fprintf(&b, "%g", value(i))
		}
		b.WriteByte(']')
	}
	for j, level := range []int{1000, 975, 950, 925, 900, 850, 800, 700, 600, 500} {
		j := j
		series(fmt.Sprintf("temperature_%dhPa", level), func(int) float64 { return float64(25 - 2*j) })
		series(fmt.Sprintf("relative_humidity_%dhPa", level), func(int) float64 { return rh[j] })
		series(fmt.Sprintf("geopotential_height_%dhPa", level), func(int) float64 { return float64(1000 * (j + 1)) })
		series(fmt.Sprintf("wind_speed_%dhPa", level), func(int) float64 { return wind })
	}
	series("cloud_cover_low", func(int) float64 { return 30 })
	series("cloud_cover_mid", func(int) float64 { return cloudMid })
	series("cloud_cover_high", func(int) float64 { return cloudHigh })
	series("precipitation", func(int) float64 { return precip })
	series("freezing_level_height", func(int) float64 { return 4200 })
	series("cape", func(i int) float64 { return float64(i * 100) })
	b.WriteString("}}")
	return b.String()
}

// ensembleForecast is the ensemble answer (#256): ECMWF's 51 members (the control run and
// _member01.._member50) over two Asia/Shanghai days (2026-09-28 and 09-29), every member the same for
// each variable, so both days come out 可信度高.
func ensembleForecast() string {
	start := time.Date(2026, 9, 28, 0, 0, 0, 0, time.FixedZone("CST", 8*3600))
	const members, hours = 51, 48
	var b strings.Builder
	b.WriteString(`{"hourly":{"time":[`)
	for i := 0; i < hours; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, "%q", start.Add(time.Duration(i)*time.Hour).Format("2006-01-02T15:04"))
	}
	b.WriteByte(']')
	series := func(name string, v float64) {
		fmt.Fprintf(&b, ",%q:[", name)
		for i := 0; i < hours; i++ {
			if i > 0 {
				b.WriteByte(',')
			}
			fmt.Fprintf(&b, "%g", v)
		}
		b.WriteByte(']')
	}
	for m := 0; m < members; m++ {
		suffix := ""
		if m > 0 {
			suffix = fmt.Sprintf("_member%02d", m)
		}
		series("temperature_2m"+suffix, 10)
		series("precipitation"+suffix, 0)
		series("wind_gusts_10m"+suffix, 3)
	}
	b.WriteString("}}")
	return b.String()
}

func newOpenMeteo(t *testing.T) *openMeteoFake {
	f := &openMeteoFake{basicElevation: 1480}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		q := r.URL.Query()
		if r.URL.Path == "/v1/ensemble" {
			f.ensembleCalls.Add(1)
			f.mu.Lock()
			f.ensembleQuery = q
			f.mu.Unlock()
			if f.fail.Load() || f.failEnsemble.Load() {
				w.WriteHeader(502)
				return
			}
			if f.ensembleJSON != "" {
				fmt.Fprint(w, f.ensembleJSON)
			} else {
				fmt.Fprint(w, ensembleForecast())
			}
			return
		}
		if r.URL.Path != "/v1/forecast" {
			w.WriteHeader(404)
			return
		}
		profile := strings.Contains(q.Get("hourly"), "geopotential_height")
		f.mu.Lock()
		if profile {
			f.profileQuery = q
		} else {
			f.query = q
		}
		f.mu.Unlock()
		if profile {
			f.profileCalls.Add(1)
			if f.fail.Load() || f.failProfile.Load() {
				w.WriteHeader(502)
				return
			}
			if f.profileJSON != "" {
				fmt.Fprint(w, f.profileJSON)
			} else {
				fmt.Fprint(w, profileForecast())
			}
			return
		}
		if f.fail.Load() {
			w.WriteHeader(502)
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
		fmt.Fprintf(w, `{"elevation":%g,"hourly":{"time":[%s],"temperature_2m":[%s],"apparent_temperature":[%s],
			"precipitation":[%s],"wind_gusts_10m":[%s],"weather_code":[%s],"wind_direction_10m":[%s]}}`,
			f.basicElevation, times.String(), temp.String(), feels.String(), precip.String(), gust.String(), code.String(), dir.String())
	}))
	t.Cleanup(f.Server.Close)
	return f
}

func (f *openMeteoFake) lastQuery() url.Values {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.query
}

func (f *openMeteoFake) lastProfileQuery() url.Values {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.profileQuery
}

func (f *openMeteoFake) lastEnsembleQuery() url.Values {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.ensembleQuery
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
func newWeatherAt(q *qweather, openMeteo string, callsPerDevicePerDay int64) *weather {
	wx := newWeather(q, openMeteo, openMeteo, http.DefaultClient, callsPerDevicePerDay)
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

// A detail request adds the 廓线's fields to every hour, its own ground height at the top, and the
// profile as the levels above that ground, bottom up (#254).
func TestWeatherDetailAddsProfileFields(t *testing.T) {
	f := newOpenMeteo(t)
	h := weatherHandler(newWeatherAt(nil, f.URL, 1000))
	res := decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=1487&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
		t.Fatalf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	if res.GroundElevation == nil || *res.GroundElevation != 3500 {
		t.Errorf("groundElevation %v", res.GroundElevation)
	}
	h0 := res.Hours[0]
	if h0.CloudLow == nil || *h0.CloudLow != 30 || h0.CloudMid == nil || *h0.CloudMid != 40 ||
		h0.CloudHigh == nil || *h0.CloudHigh != 50 {
		t.Errorf("cloud layers %v %v %v", h0.CloudLow, h0.CloudMid, h0.CloudHigh)
	}
	if h0.FreezingLevel == nil || *h0.FreezingLevel != 4200 {
		t.Errorf("freezingLevel %v", h0.FreezingLevel)
	}
	// CAPE is 100 J/kg per hour from the forecast's first hour: 200 low, 300 and 1000 medium, 1100 high.
	for _, c := range []struct {
		hour int
		want api.WeatherHourThunderPotential
	}{{0, api.WeatherHourThunderPotentialLow}, {1, api.WeatherHourThunderPotentialMedium},
		{8, api.WeatherHourThunderPotentialMedium}, {9, api.WeatherHourThunderPotentialHigh}} {
		if tp := res.Hours[c.hour].ThunderPotential; tp == nil || *tp != c.want {
			t.Errorf("hour %d: thunderPotential %v, want %q", c.hour, tp, c.want)
		}
	}
	// The ground is at 3500 m: the 1000 m steps keep 4000 m (925 hPa) up to 10000 m (500 hPa), 7 levels.
	p := h0.Profile
	if p == nil || len(*p) != 7 {
		t.Fatalf("profile %v", p)
	}
	first, last := (*p)[0], (*p)[6]
	if first.Height != 4000 || first.Temp != 19 || first.Rh != 55 {
		t.Errorf("profile bottom %+v", first)
	}
	if last.Height != 10000 || last.Temp != 7 || last.Rh != 85 {
		t.Errorf("profile top %+v", last)
	}
	for i := 1; i < len(*p); i++ {
		if (*p)[i].Height <= (*p)[i-1].Height {
			t.Errorf("profile not bottom up: %v", *p)
			break
		}
	}
	// The 廓线 is its own Open-Meteo call: the 0.1° cell, elevation=nan, and the 46 variables.
	q := f.lastProfileQuery()
	if q.Get("elevation") != "nan" {
		t.Errorf("廓线 elevation %q", q.Get("elevation"))
	}
	if q.Get("latitude") != "34.0" || q.Get("longitude") != "107.8" {
		t.Errorf("廓线 asked for %s,%s", q.Get("latitude"), q.Get("longitude"))
	}
	if vars := strings.Split(q.Get("hourly"), ","); len(vars) != 46 {
		t.Errorf("廓线 %d variables, want 46: %v", len(vars), vars)
	}
	for _, v := range []string{"temperature_1000hPa", "relative_humidity_975hPa", "geopotential_height_500hPa",
		"wind_speed_500hPa", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high", "precipitation",
		"freezing_level_height", "cape"} {
		if !strings.Contains(q.Get("hourly"), v) {
			t.Errorf("廓线 missing %s", v)
		}
	}
}

// cloudLvl is one 廓线 level for the 云海 tests: a pressure level's height (m), temperature (°C),
// relative humidity (%) and wind speed (m/s) (#255).
func cloudLvl(hpa, height int, temp, rh, wind float64) cloudLevel {
	return cloudLevel{hpa: hpa, height: float64(height), temp: temp, rh: rh, wind: wind}
}

// typicalCloudSeaLevels is 典型的云海: 山下 saturated, an inversion at the place, 高空 dry (#240).
func typicalCloudSeaLevels() []cloudLevel {
	return []cloudLevel{
		cloudLvl(925, 750, 8, 95, 3),
		cloudLvl(850, 1500, 5, 93, 3),
		cloudLvl(800, 2000, 8, 30, 3), // 逆温: warmer above the place than below
		cloudLvl(700, 3000, 2, 20, 3),
		cloudLvl(600, 4200, -5, 15, 3),
		cloudLvl(500, 5500, -20, 10, 3),
	}
}

// The 云海 rule is #240's 三段法 plus #243's details: a place 200 m above the cell's ground and below
// the 500 hPa level gets a 档位, and a cloud top when there is cloud below (#255).
func TestCloudSeaRule(t *testing.T) {
	cases := []struct {
		name string
		in   cloudSeaInput
		ok   bool
		tier api.WeatherHourCloudSea
		top  float64 // -1 when there is no cloud top
	}{
		{
			name: "典型云海: 下层饱和、逆温、上层干",
			in:   cloudSeaInput{levels: typicalCloudSeaLevels(), ground: 1000, place: 1700},
			ok:   true, tier: api.WeatherHourCloudSeaHigh, top: 1500,
		},
		{
			name: "地点海拔离格点地面不到 200 m",
			in:   cloudSeaInput{levels: typicalCloudSeaLevels(), ground: 1000, place: 1100},
			ok:   false,
		},
		{
			name: "高于 500 hPa 层",
			in:   cloudSeaInput{levels: typicalCloudSeaLevels(), ground: 1000, place: 6000},
			ok:   false,
		},
		{
			name: "下层没有云",
			in: cloudSeaInput{levels: []cloudLevel{
				cloudLvl(850, 1500, 5, 50, 3), cloudLvl(800, 2000, 8, 30, 3),
				cloudLvl(700, 3000, 2, 20, 3), cloudLvl(500, 5500, -20, 10, 3),
			}, ground: 1000, place: 1700},
			ok: true, tier: api.WeatherHourCloudSeaLow, top: -1,
		},
		{
			name: "上层也有云",
			in:   cloudSeaInput{levels: typicalCloudSeaLevels(), ground: 1000, place: 1700, cloudHigh: 60},
			ok:   true, tier: api.WeatherHourCloudSeaMedium, top: 1500,
		},
		{
			name: "有降水",
			in:   cloudSeaInput{levels: typicalCloudSeaLevels(), ground: 1000, place: 1700, precipitation: 0.1},
			ok:   true, tier: api.WeatherHourCloudSeaLow, top: 1500,
		},
		{
			name: "雨不到 0.1 mm/h 不算降水",
			in:   cloudSeaInput{levels: typicalCloudSeaLevels(), ground: 1000, place: 1700, precipitation: 0.05},
			ok:   true, tier: api.WeatherHourCloudSeaHigh, top: 1500,
		},
		{
			name: "地点在云里",
			in: cloudSeaInput{levels: []cloudLevel{
				cloudLvl(850, 1500, 5, 96, 3), cloudLvl(800, 2000, 8, 96, 3),
				cloudLvl(700, 3000, 2, 20, 3), cloudLvl(500, 5500, -20, 10, 3),
			}, ground: 1000, place: 1700},
			ok: true, tier: api.WeatherHourCloudSeaLow, top: 1500,
		},
		{
			// The 700 hPa level itself takes the 高空 band's Hc 0.65: at 78% its N is 0.14, enough for
			// 中; with the low band's 0.80 it would be 0 and the hour 低 (#240).
			name: "700 hPa 层按高空档的 Hc",
			in: cloudSeaInput{levels: []cloudLevel{
				cloudLvl(700, 3000, 5, 78, 3), cloudLvl(600, 4200, -5, 20, 3),
				cloudLvl(500, 5500, -20, 10, 3),
			}, ground: 1000, place: 3500},
			ok: true, tier: api.WeatherHourCloudSeaMedium, top: 3000,
		},
		{
			// 山下 is the levels at or below 地点海拔 − 100 m: the layer exactly 100 m under the place
			// counts, so this is 高 rather than 低 (#240).
			name: "山下层正好在地点下方 100 m",
			in: cloudSeaInput{levels: []cloudLevel{
				cloudLvl(850, 1500, 5, 96, 3), cloudLvl(800, 2000, 8, 30, 3),
				cloudLvl(700, 3000, 2, 20, 3), cloudLvl(500, 5500, -20, 10, 3),
			}, ground: 1000, place: 1600},
			ok: true, tier: api.WeatherHourCloudSeaHigh, top: 1500,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			v, ok := cloudSea(c.in)
			if ok != c.ok {
				t.Fatalf("ok %v, want %v", ok, c.ok)
			}
			if !ok {
				return
			}
			if v.tier != c.tier {
				t.Errorf("tier %q, want %q", v.tier, c.tier)
			}
			if c.top < 0 {
				if v.hasTop {
					t.Errorf("cloudTop %v, want none", v.top)
				}
				return
			}
			if !v.hasTop || v.top != c.top {
				t.Errorf("cloudTop %v (has %v), want %v", v.top, v.hasTop, c.top)
			}
		})
	}
}

// A place above the cell's ground gets 云海 hour by hour, with the cloud top when there is cloud
// below (#255).
func TestWeatherDetailAddsCloudSea(t *testing.T) {
	f := newOpenMeteo(t)
	// 3500 m ground, saturated below 4200 m and dry above: a place at 4200 m looks over a 云海 whose top
	// is the 4000 m layer.
	rh := []float64{96, 96, 96, 96, 30, 25, 25, 25, 25, 25}
	f.profileJSON = profileAnswer(3500, rh, 5, 5, 0, 3)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&ele=4200&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
		t.Fatalf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	for _, h := range res.Hours[:3] {
		if h.CloudSea == nil || *h.CloudSea != api.WeatherHourCloudSeaHigh {
			t.Fatalf("cloudSea %v", h.CloudSea)
		}
		if h.CloudTop == nil || *h.CloudTop != 4000 {
			t.Errorf("cloudTop %v, want 4000", h.CloudTop)
		}
	}
}

// A place that isn't 200 m above the cell's ground gets no 云海 and no cloud top (#255).
func TestWeatherDetailNoCloudSeaBelowGround(t *testing.T) {
	f := newOpenMeteo(t)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&ele=1487&detail=true", "X-Device-Id", "d"))
	if h0 := res.Hours[0]; h0.CloudSea != nil || h0.CloudTop != nil {
		t.Errorf("云海 fields from a place 1487 m below the 3500 m ground: %+v", h0)
	}
}

// Without ele the place's 地点海拔 is the basic answer's elevation, Open-Meteo's DEM height (#255).
func TestWeatherCloudSeaWithoutEleUsesBaseElevation(t *testing.T) {
	f := newOpenMeteo(t)
	f.basicElevation = 4000 // the DEM height at the place, 500 m above the 3500 m cell ground
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&detail=true", "X-Device-Id", "d"))
	if h0 := res.Hours[0]; h0.CloudSea == nil {
		t.Fatalf("no cloudSea without ele: %+v", h0)
	}
}

// The 廓线's 0.1° cell is rounded from the place's own coordinates, not from the already-rounded 0.01°
// cell again: 33.947's 0.01° cell is 33.95, whose nearest 0.1° is 33.9, not 34.0 (#254).
func TestWeatherProfileCellRoundsFromThePlace(t *testing.T) {
	f := newOpenMeteo(t)
	h := weatherHandler(newWeatherAt(nil, f.URL, 1000))
	decode(t, get(h, "/v1/weather?lat=33.947&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if got := f.lastProfileQuery().Get("latitude"); got != "33.9" {
		t.Errorf("廓线 latitude %q, want 33.9", got)
	}
	// The wind comes in the service's unit, m/s, like the basic forecast's.
	if got := f.lastProfileQuery().Get("wind_speed_unit"); got != "ms" {
		t.Errorf("廓线 wind_speed_unit %q, want ms", got)
	}
}

// Without detail the 廓线 isn't asked for, and none of its fields are set (#254).
func TestWeatherWithoutDetailSendsNoProfileRequest(t *testing.T) {
	f := newOpenMeteo(t)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if n := f.profileCalls.Load(); n != 0 {
		t.Errorf("%d 廓线 calls without detail", n)
	}
	if res.GroundElevation != nil {
		t.Errorf("groundElevation %v", res.GroundElevation)
	}
	if h0 := res.Hours[0]; h0.CloudLow != nil || h0.CloudMid != nil || h0.CloudHigh != nil || h0.FreezingLevel != nil ||
		h0.ThunderPotential != nil || h0.Profile != nil {
		t.Errorf("detail fields without detail: %+v", h0)
	}
}

// The 廓线 is cached by 0.1° cell for 3 hours (#254): two places in one cell share it, and it expires.
func TestWeatherCachesProfileByTenthDegreeCell(t *testing.T) {
	f := newOpenMeteo(t)
	now := nowAt
	wx := newWeather(nil, f.URL, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return now }
	h := weatherHandler(wx)
	// 33.9601 and 33.9510 are different 0.01° cells (the basic forecast's key) but one 0.1° cell.
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=100&detail=true", "X-Device-Id", "d"))
	decode(t, get(h, "/v1/weather?lat=33.9510&lon=107.7712&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.profileCalls.Load(); n != 1 {
		t.Fatalf("same 0.1° cell: %d 廓线 calls, want 1", n)
	}
	now = nowAt.Add(2 * time.Hour)
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.profileCalls.Load(); n != 1 {
		t.Fatalf("within the 3 h TTL: %d 廓线 calls, want 1", n)
	}
	now = nowAt.Add(4 * time.Hour)
	decode(t, get(h, "/v1/weather?lat=33.9601&lon=107.7712&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.profileCalls.Load(); n != 2 {
		t.Fatalf("after the 3 h TTL: %d 廓线 calls, want 2", n)
	}
}

// A 廓线 that fails leaves the basic forecast alone: ok, hours there, no detail fields (#254).
func TestWeatherProfileFailsLeavesBasicForecast(t *testing.T) {
	f := newOpenMeteo(t)
	f.failProfile.Store(true)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)), "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
		t.Fatalf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	if res.GroundElevation != nil {
		t.Errorf("groundElevation %v", res.GroundElevation)
	}
	if h0 := res.Hours[0]; h0.CloudLow != nil || h0.FreezingLevel != nil || h0.ThunderPotential != nil || h0.Profile != nil {
		t.Errorf("detail fields from a failed 廓线: %+v", h0)
	}
	// Still attributed to Open-Meteo, whose basic forecast did come back.
	if len(res.Sources) != 1 || res.Sources[0] != api.OpenMeteo {
		t.Errorf("sources %v", res.Sources)
	}
}

// When the server's own allowance can't fit the 廓线 or the ensemble, neither is sent: the basic
// forecast comes back ok, without the detail fields (#254, #256).
func TestWeatherProfileQuotaExhaustedLeavesBasicForecast(t *testing.T) {
	f := newOpenMeteo(t)
	wx := newWeatherAt(nil, f.URL, 1000)
	// Room for the basic forecast (1 call) but neither the 廓线 (4.6 more) nor the ensemble (15.3 more).
	wx.budget.dayMax = 2 * weightUnit
	wx.budget.monthMax = 100 * weightUnit
	res := decode(t, get(weatherHandler(wx), "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
		t.Fatalf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	if res.GroundElevation != nil || res.Hours[0].CloudLow != nil || res.Hours[0].Profile != nil || res.Days != nil {
		t.Errorf("detail fields without room for the 廓线: %+v", res.Hours[0])
	}
	if n := f.profileCalls.Load(); n != 0 {
		t.Errorf("%d 廓线 calls, want 0", n)
	}
	if n := f.ensembleCalls.Load(); n != 0 {
		t.Errorf("%d ensemble calls, want 0", n)
	}
}

// A detail request is charged the basic call plus the 廓线's 4.6 and the ensemble's 15 to the device's
// own daily quota (#254, #256).
func TestWeatherDetailChargesProfileAndEnsembleWeightToDevice(t *testing.T) {
	f := newOpenMeteo(t)
	// 20 calls can't cover a detail request's 1 + 4.6 + 15 = 20.6.
	if w := get(weatherHandler(newWeatherAt(nil, f.URL, 20)), "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"); w.Code != 429 {
		t.Fatalf("detail over a 20-call budget: %d %s", w.Code, w.Body)
	}
	if n := f.calls.Load(); n != 0 {
		t.Errorf("%d Open-Meteo calls, want 0", n)
	}
	// 21 calls cover it, so a second one doesn't fit even though every answer is cached.
	h := weatherHandler(newWeatherAt(nil, f.URL, 21))
	if w := get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"); w.Code != 200 {
		t.Fatalf("first detail: %d %s", w.Code, w.Body)
	}
	if w := get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"); w.Code != 429 {
		t.Fatalf("second detail: %d %s", w.Code, w.Body)
	}
}

func TestWeatherCachesByCellAndElevationBand(t *testing.T) {
	f := newOpenMeteo(t)
	now := nowAt
	wx := newWeather(nil, f.URL, f.URL, http.DefaultClient, 1000)
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

// The device's daily quota is charged on every request, cache hit or not (#247).
func TestWeatherDeviceQuotaChargedOnCacheHit(t *testing.T) {
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

// Once the server's own daily Open-Meteo allowance is spent, a request that would need a call gets
// 200 with forecast quota_exhausted and its warnings; a cached forecast is still served (#247).
func TestWeatherDailyBudgetStopsOpenMeteo(t *testing.T) {
	f := newOpenMeteo(t)
	q, _ := newQWeather(t)
	now := nowAt
	wx := newWeather(q, f.URL, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return now }
	wx.budget.dayMax = 2 * weightUnit
	h := weatherHandler(wx)

	decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	res := decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=200", "X-Device-Id", "d"))
	if n := f.calls.Load(); n != 2 {
		t.Fatalf("%d Open-Meteo calls, want 2", n)
	}
	if res.Forecast != api.WeatherForecastOk {
		t.Fatalf("second: forecast %q", res.Forecast)
	}
	// The allowance is spent: no call, quota_exhausted, warnings as usual (a third height band is its
	// own cache key, so this really would ask Open-Meteo).
	res = decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=300", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastQuotaExhausted || len(res.Hours) != 0 {
		t.Errorf("over budget: forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	if len(res.Warnings) != 1 || res.WarningsFailed != nil {
		t.Errorf("over budget warnings %+v failed %v", res.Warnings, res.WarningsFailed)
	}
	if len(res.Sources) != 1 || res.Sources[0] != api.Qweather {
		t.Errorf("over budget sources %v", res.Sources)
	}
	if n := f.calls.Load(); n != 2 {
		t.Errorf("over budget: %d calls, want 2", n)
	}
	// The first place's cached forecast still comes back.
	res = decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
		t.Errorf("cached: forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
}

// The monthly allowance stops calls the same way, on the first day of the next UTC month it resets (#247).
func TestWeatherMonthlyBudgetStopsOpenMeteo(t *testing.T) {
	f := newOpenMeteo(t)
	now := nowAt
	wx := newWeather(nil, f.URL, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return now }
	wx.budget.dayMax = 100 * weightUnit
	wx.budget.monthMax = 1 * weightUnit
	wx.budget.logf = func(string, ...any) {}
	h := weatherHandler(wx)

	decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	res := decode(t, get(h, "/v1/weather?lat=33.97&lon=107.78&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastQuotaExhausted {
		t.Errorf("over the month: forecast %q", res.Forecast)
	}
	if n := f.calls.Load(); n != 1 {
		t.Errorf("over the month: %d calls, want 1", n)
	}
	// The next UTC month starts the allowance over.
	now = time.Date(2026, 10, 1, 0, 30, 0, 0, time.UTC)
	res = decode(t, get(h, "/v1/weather?lat=33.98&lon=107.79&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk {
		t.Errorf("new month: forecast %q", res.Forecast)
	}
}

// Requests racing for the last of the server's allowance can't overrun it: checking and charging share
// one lock, so exactly as many calls go out as the allowance fits (#247).
func TestWeatherBudgetNotOverrunByConcurrentRequests(t *testing.T) {
	f := newOpenMeteo(t)
	wx := newWeather(nil, f.URL, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return nowAt }
	wx.budget.dayMax = 3 * weightUnit
	wx.budget.logf = func(string, ...any) {}
	h := weatherHandler(wx)

	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			get(h, fmt.Sprintf("/v1/weather?lat=33.%02d&lon=107.77&ele=100", i), "X-Device-Id", fmt.Sprintf("d%d", i))
		}()
	}
	wg.Wait()
	if n := f.calls.Load(); n != 3 {
		t.Errorf("%d Open-Meteo calls, want the 3 the allowance fits", n)
	}
}

// A finished UTC day's weighted total goes to the log from the first request after the day changes;
// past the WARN line it is a WARN (#247).
func TestWeatherDailyUsageLog(t *testing.T) {
	f := newOpenMeteo(t)
	now := nowAt
	var lines []string
	wx := newWeather(nil, f.URL, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return now }
	wx.budget.dayMax, wx.budget.monthMax = 100*weightUnit, 100*weightUnit
	wx.budget.warnMax = 3 * weightUnit
	wx.budget.logf = func(format string, args ...any) { lines = append(lines, fmt.Sprintf(format, args...)) }
	h := weatherHandler(wx)

	for _, q := range []string{"lat=33.96&lon=107.77&ele=100", "lat=33.97&lon=107.78&ele=100",
		"lat=33.98&lon=107.79&ele=100", "lat=33.99&lon=107.80&ele=100"} {
		decode(t, get(h, "/v1/weather?"+q, "X-Device-Id", "d"))
	}
	if len(lines) != 0 {
		t.Fatalf("logged mid-day: %v", lines)
	}
	// The next UTC day: the first request writes the finished day, and only the first.
	now = now.AddDate(0, 0, 1)
	decode(t, get(h, "/v1/weather?lat=34.00&lon=107.81&ele=100", "X-Device-Id", "d"))
	decode(t, get(h, "/v1/weather?lat=34.01&lon=107.82&ele=100", "X-Device-Id", "d"))
	if len(lines) != 1 {
		t.Fatalf("lines %v, want one summary", lines)
	}
	if l := lines[0]; !strings.HasPrefix(l, "WARN") || !strings.Contains(l, "2026-09-28") || !strings.Contains(l, "4.0") {
		t.Errorf("WARN day: %q", l)
	}
	// The day under the WARN line is a plain line.
	now = now.AddDate(0, 0, 1)
	decode(t, get(h, "/v1/weather?lat=34.02&lon=107.83&ele=100", "X-Device-Id", "d"))
	if len(lines) != 2 {
		t.Fatalf("lines %v, want the second day's", lines)
	}
	if l := lines[1]; strings.HasPrefix(l, "WARN") || !strings.Contains(l, "2026-09-29") || !strings.Contains(l, "2.0") {
		t.Errorf("plain day: %q", l)
	}
}

// ensDayTimes is days Asia/Shanghai days hour by hour from 2026-09-28 00:00, for ensembleDays.
func ensDayTimes(days int) []string {
	start := time.Date(2026, 9, 28, 0, 0, 0, 0, time.FixedZone("CST", 8*3600))
	out := make([]string, 0, days*24)
	for i := 0; i < days*24; i++ {
		out = append(out, start.Add(time.Duration(i)*time.Hour).Format("2006-01-02T15:04"))
	}
	return out
}

// ensMember is a member whose values are the same every hour: temperature temp (°C) and gust gust (m/s)
// throughout, and precip mm in the day's first hour (its daily total). hours 0 is a member with no data
// at all, for the too-few-members rule.
func ensMember(hours int, temp, precip, gust float64) ensembleMember {
	tempS := make([]*float64, hours)
	precipS := make([]*float64, hours)
	gustS := make([]*float64, hours)
	for i := 0; i < hours; i++ {
		tv, gv := temp, gust
		pv := 0.0
		tempS[i], precipS[i], gustS[i] = &tv, &pv, &gv
	}
	if hours > 0 {
		pv := precip
		precipS[0] = &pv
	}
	return ensembleMember{temp: tempS, precip: precipS, gust: gustS}
}

// The 预报可信度 bands and the day's worst (#256, #244): each item's bands, the worst of the three,
// lowBy only for the items at the worst, and a day fewer than half the members cover left out.
func TestEnsembleDaysConfidence(t *testing.T) {
	const day = 24
	cases := []struct {
		name      string
		times     []string
		members   []ensembleMember
		wantDates []string
		wantConf  []api.WeatherDayConfidence
		wantLowBy [][]api.WeatherDayLowBy // nil: lowBy absent
	}{
		{
			name:      "三项都高",
			members:   []ensembleMember{ensMember(day, 10, 0, 3), ensMember(day, 11, 0, 4)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceHigh},
			wantLowBy: [][]api.WeatherDayLowBy{nil},
		},
		{
			name:      "气温中",
			members:   []ensembleMember{ensMember(day, 0, 0, 3), ensMember(day, 4, 0, 4)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceMedium},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Temp}},
		},
		{
			name:      "气温低",
			members:   []ensembleMember{ensMember(day, 0, 0, 3), ensMember(day, 8, 0, 4)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceLow},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Temp}},
		},
		{
			name:      "降水低（半数成员有雨）",
			members:   []ensembleMember{ensMember(day, 0, 2, 3), ensMember(day, 0, 0, 3)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceLow},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Precip}},
		},
		{
			name: "降水中（四分之一成员有雨）",
			members: []ensembleMember{ensMember(day, 0, 2, 3), ensMember(day, 0, 0, 3),
				ensMember(day, 0, 0, 3), ensMember(day, 0, 0, 3)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceMedium},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Precip}},
		},
		{
			name:      "降水高（全部成员有雨）",
			members:   []ensembleMember{ensMember(day, 0, 2, 3), ensMember(day, 0, 2, 3)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceHigh},
			wantLowBy: [][]api.WeatherDayLowBy{nil},
		},
		{
			name:      "阵风中",
			members:   []ensembleMember{ensMember(day, 0, 0, 0), ensMember(day, 0, 0, 6)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceMedium},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Gust}},
		},
		{
			name:      "阵风低",
			members:   []ensembleMember{ensMember(day, 0, 0, 0), ensMember(day, 0, 0, 10)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceLow},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Gust}},
		},
		{
			name: "取最差一档，lowBy 只列最差档的项",
			// σT 2（中）、p 0.5（低）、σG 5（低）：整天低，lowBy 是降水、阵风，不含气温。
			members: []ensembleMember{ensMember(day, 0, 0, 0), ensMember(day, 0, 0, 0),
				ensMember(day, 4, 2, 10), ensMember(day, 4, 2, 10)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceLow},
			wantLowBy: [][]api.WeatherDayLowBy{{api.Precip, api.Gust}},
		},
		{
			name: "成员不到一半的那天不给",
			members: []ensembleMember{ensMember(day, 0, 0, 0), ensMember(0, 0, 0, 0),
				ensMember(0, 0, 0, 0), ensMember(0, 0, 0, 0)},
			wantDates: nil,
		},
		{
			name: "正好一半成员的那天给",
			members: []ensembleMember{ensMember(day, 0, 0, 0), ensMember(day, 0, 0, 0),
				ensMember(0, 0, 0, 0), ensMember(0, 0, 0, 0)},
			wantDates: []string{"2026-09-28"}, wantConf: []api.WeatherDayConfidence{api.WeatherDayConfidenceHigh},
			wantLowBy: [][]api.WeatherDayLowBy{nil},
		},
		{
			name:      "跨两天按日期排",
			times:     ensDayTimes(2),
			members:   []ensembleMember{ensMember(2*day, 10, 0, 3), ensMember(2*day, 10, 0, 3)},
			wantDates: []string{"2026-09-28", "2026-09-29"},
			wantConf:  []api.WeatherDayConfidence{api.WeatherDayConfidenceHigh, api.WeatherDayConfidenceHigh},
			wantLowBy: [][]api.WeatherDayLowBy{nil, nil},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			times := c.times
			if times == nil {
				times = ensDayTimes(1)
			}
			got := ensembleDays(times, c.members)
			if len(got) != len(c.wantDates) {
				t.Fatalf("days %+v, want dates %v", got, c.wantDates)
			}
			for i, d := range got {
				if d.Date.String() != c.wantDates[i] {
					t.Errorf("day %d: date %s, want %s", i, d.Date.String(), c.wantDates[i])
				}
				if d.Confidence != c.wantConf[i] {
					t.Errorf("day %d: confidence %q, want %q", i, d.Confidence, c.wantConf[i])
				}
				if want := c.wantLowBy[i]; want == nil {
					if d.LowBy != nil {
						t.Errorf("day %d: lowBy %v, want none", i, *d.LowBy)
					}
				} else if d.LowBy == nil || !slices.Equal(*d.LowBy, want) {
					t.Errorf("day %d: lowBy %v, want %v", i, d.LowBy, want)
				}
			}
		})
	}
}

// A detail request adds the days' 预报可信度 from the ECMWF ensemble (#256).
func TestWeatherDetailAddsDays(t *testing.T) {
	f := newOpenMeteo(t)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk {
		t.Fatalf("forecast %q", res.Forecast)
	}
	if res.Days == nil || len(*res.Days) != 2 {
		t.Fatalf("days %v, want the fake's two days", res.Days)
	}
	for _, d := range *res.Days {
		if d.Confidence != api.WeatherDayConfidenceHigh || d.LowBy != nil {
			t.Errorf("day %s: %+v", d.Date.String(), d)
		}
	}
	// The request is the ECMWF ensemble, for the 0.25° cell, in Asia/Shanghai.
	q := f.lastEnsembleQuery()
	if q.Get("models") != "ecmwf_ifs025" || q.Get("timezone") != "Asia/Shanghai" || q.Get("wind_speed_unit") != "ms" {
		t.Errorf("ensemble query %v", q)
	}
	if q.Get("latitude") != "34.00" || q.Get("longitude") != "107.75" {
		t.Errorf("ensemble asked for %s,%s", q.Get("latitude"), q.Get("longitude"))
	}
	if got := q.Get("hourly"); got != "temperature_2m,precipitation,wind_gusts_10m" {
		t.Errorf("ensemble hourly %q", got)
	}
}

// Without detail the ensemble isn't asked for, and days is absent (#256).
func TestWeatherEnsembleOnlyOnDetail(t *testing.T) {
	f := newOpenMeteo(t)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&ele=100", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk {
		t.Fatalf("forecast %q", res.Forecast)
	}
	if res.Days != nil {
		t.Errorf("days without detail: %v", res.Days)
	}
	if n := f.ensembleCalls.Load(); n != 0 {
		t.Errorf("%d ensemble calls without detail, want 0", n)
	}
}

// When the ensemble fails, the forecast is still ok with its hours, and days is absent (#256).
func TestWeatherEnsembleFailureLeavesForecastOk(t *testing.T) {
	f := newOpenMeteo(t)
	f.failEnsemble.Store(true)
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk || len(res.Hours) != weatherHours {
		t.Fatalf("forecast %q, hours %d", res.Forecast, len(res.Hours))
	}
	if res.Days != nil {
		t.Errorf("days from a failed ensemble: %v", res.Days)
	}
	if res.GroundElevation == nil {
		t.Errorf("the 廓线's fields should still be there")
	}
}

// A successful ensemble whose every day is short of half its members gives days present but empty,
// unlike a failed ensemble's absent days (#256).
func TestWeatherEnsembleEmptyDaysPresent(t *testing.T) {
	f := newOpenMeteo(t)
	// One complete member and three with no series at all: every day is under half, so days is empty.
	f.ensembleJSON = `{"hourly":{"time":["2026-09-28T00:00","2026-09-28T01:00"],
		"temperature_2m":[10,10],"precipitation":[0,0],"wind_gusts_10m":[3,3],
		"temperature_2m_member01":[],"precipitation_member01":[],"wind_gusts_10m_member01":[],
		"temperature_2m_member02":[],"precipitation_member02":[],"wind_gusts_10m_member02":[],
		"temperature_2m_member03":[],"precipitation_member03":[],"wind_gusts_10m_member03":[]}}`
	res := decode(t, get(weatherHandler(newWeatherAt(nil, f.URL, 1000)),
		"/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if res.Forecast != api.WeatherForecastOk {
		t.Fatalf("forecast %q", res.Forecast)
	}
	if res.Days == nil || len(*res.Days) != 0 {
		t.Errorf("days %v, want present but empty", res.Days)
	}
}

// The ensemble is cached by its own 0.25° cell for 6 hours (#256).
func TestWeatherEnsembleCachesPerQuarterDegree(t *testing.T) {
	f := newOpenMeteo(t)
	now := nowAt
	wx := newWeather(nil, f.URL, f.URL, http.DefaultClient, 1000)
	wx.now = func() time.Time { return now }
	h := weatherHandler(wx)
	// 33.96 and 33.98 fall in the same 0.25° cell (34.00), so the ensemble is asked for once.
	decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	decode(t, get(h, "/v1/weather?lat=33.98&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.ensembleCalls.Load(); n != 1 {
		t.Fatalf("same 0.25° cell: %d ensemble calls, want 1", n)
	}
	// Another 0.25° cell is its own key.
	decode(t, get(h, "/v1/weather?lat=34.2&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.ensembleCalls.Load(); n != 2 {
		t.Fatalf("another 0.25° cell: %d ensemble calls, want 2", n)
	}
	// Still cached five hours on; expired seven hours on.
	now = nowAt.Add(5 * time.Hour)
	decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.ensembleCalls.Load(); n != 2 {
		t.Fatalf("within the 6 h TTL: %d ensemble calls, want 2", n)
	}
	now = nowAt.Add(7 * time.Hour)
	decode(t, get(h, "/v1/weather?lat=33.96&lon=107.77&ele=100&detail=true", "X-Device-Id", "d"))
	if n := f.ensembleCalls.Load(); n != 3 {
		t.Fatalf("after the 6 h TTL: %d ensemble calls, want 3", n)
	}
}
