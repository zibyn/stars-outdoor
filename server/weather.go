package main

// 沿途天气 through the server (spec §2.9): the app sends points along a track with their expected
// arrival times and gets each one's forecast hour plus the official warnings. 和风天气 first, Open-Meteo
// for any cell 和风 can't answer; the app never sees which, nor the 和风 credentials.

import (
	"cmp"
	"context"
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/errgroup"
	"golang.org/x/sync/singleflight"

	"stars-trail/server/api"
)

const (
	maxWeatherPoints = 200 // a week hour by hour, in one cell
	// §2.9: a day hike is 10–30 points, re-asked every 2 h while recording; 3000 is ~100 of those a day.
	weatherCellsPerDay = 3000
	// 和风's hourly forecast has no gusts: they come from Open-Meteo, or, for hours it lacks, as
	// mean wind × 1.5 (a typical overland gust factor; mountains gust harder).
	gustFactor = 1.5
)

// cell is a 0.01° grid square (issue #11), in hundredths of a degree.
type cell struct{ lat, lon int }

// latText and lonText are the cell's corner as "33.96", as the providers are asked.
func (c cell) latText() string { return strconv.FormatFloat(float64(c.lat)/100, 'f', 2, 64) }
func (c cell) lonText() string { return strconv.FormatFloat(float64(c.lon)/100, 'f', 2, 64) }

// forecast is one cell's answer: hours keyed by Unix hour (seconds / 3600), Point left unset.
// Open-Meteo has no warnings: the app says so when it's among the sources.
type forecast struct {
	source   api.WeatherSources
	hours    map[int64]api.WeatherHour
	warnings []api.WeatherWarning
}

type weather struct {
	qweather  *qweather // nil: not configured, Open-Meteo only
	openMeteo string    // "https://api.open-meteo.com"
	client    *http.Client
	// ponytail: daily cell quotas in memory, reset on restart (single instance, §3.2).
	devices, ips *limiter
	// Forecasts are cached for the clock hour they were fetched in (issue #11: per cell and forecast hour).
	// ponytail: the whole cache is dropped when the hour turns (which also bounds it), so every busy
	// cell is refetched on the hour; per-entry expiry if that burst hits provider limits.
	mu       sync.Mutex
	hour     int64
	cache    map[cell]*forecast
	inflight singleflight.Group
}

func newWeather(q *qweather, openMeteo string, client *http.Client, cellsPerDay int64) *weather {
	return &weather{qweather: q, openMeteo: openMeteo, client: client,
		devices: &limiter{max: cellsPerDay, period: 86400}, ips: &limiter{max: cellsPerDay * ipShare, period: 86400}}
}

// quotaKeys are the keys a daily quota is charged under: the device ID (the IP without one), and the IP.
func quotaKeys(ctx context.Context, deviceID *string) (device, ip string) {
	ip = clientIP(ctx)
	if deviceID != nil && *deviceID != "" {
		return "id " + *deviceID, ip
	}
	return ip, ip
}

// X-Device-Id is anonymous and client-chosen, so rotating it is capped by a looser per-IP quota
// (loose because carrier NAT puts many phones behind one IP).
const ipShare = 10

func (s *server) PostWeather(ctx context.Context, req api.PostWeatherRequestObject) (api.PostWeatherResponseObject, error) {
	invalid := api.PostWeather400JSONResponse{Error: api.ErrorCodeInvalidRequest}
	unavailable := api.PostWeather503JSONResponse{DataUnavailableJSONResponse: api.DataUnavailableJSONResponse{Error: api.ErrorCodeDataUnavailable}}
	pts := req.Body.Points
	if len(pts) == 0 || len(pts) > maxWeatherPoints {
		return invalid, nil
	}
	cellOf := make([]cell, len(pts))
	var cells []cell
	for i, p := range pts {
		if !(p.Lat >= -90 && p.Lat <= 90 && p.Lon >= -180 && p.Lon <= 180) {
			return invalid, nil
		}
		cellOf[i] = cell{int(math.Round(p.Lat * 100)), int(math.Round(p.Lon * 100))}
		if !slices.Contains(cells, cellOf[i]) {
			cells = append(cells, cellOf[i])
		}
	}
	w := s.weather
	if w == nil {
		return unavailable, nil
	}
	// Charged per cell asked for, cached or not; the IP gets a looser cap (device IDs are rotatable).
	device, ip := quotaKeys(ctx, req.Params.XDeviceId)
	n := int64(len(cells))
	if !w.devices.fits(device, n) || !w.ips.fits(ip, n) {
		return api.PostWeather429JSONResponse{Error: api.ErrorCodeDailyQuotaExceeded, QuotaCells: &w.devices.max}, nil
	}
	w.devices.take(device, n)
	w.ips.take(ip, n)

	var mu sync.Mutex
	got := map[cell]*forecast{}
	var g errgroup.Group
	g.SetLimit(8)
	for _, c := range cells {
		g.Go(func() error {
			if f, err := w.forecast(ctx, c); err == nil {
				mu.Lock()
				got[c] = f
				mu.Unlock()
			}
			return nil
		})
	}
	g.Wait()
	if len(got) == 0 {
		return unavailable, nil
	}

	res := api.PostWeather200JSONResponse{Hours: []api.WeatherHour{}, Warnings: []api.WeatherWarning{}, Sources: []api.WeatherSources{}}
	for i, p := range pts {
		f := got[cellOf[i]]
		if f == nil {
			continue
		}
		if h, ok := f.hours[floorDiv(p.Time, 3600)]; ok {
			h.Point = i
			res.Hours = append(res.Hours, h)
		}
	}
	for _, c := range cells {
		f := got[c]
		if f == nil {
			continue
		}
		if !slices.Contains(res.Sources, f.source) {
			res.Sources = append(res.Sources, f.source)
		}
		for _, a := range f.warnings {
			if !slices.ContainsFunc(res.Warnings, func(b api.WeatherWarning) bool { return b.Id == a.Id }) {
				res.Warnings = append(res.Warnings, a)
			}
		}
	}
	return res, nil
}

func floorDiv(a, b int64) int64 {
	q := a / b
	if a%b != 0 && a < 0 {
		q--
	}
	return q
}

// forecast is c's forecast from cache, or fetched once however many requests want it at the same time.
func (w *weather) forecast(ctx context.Context, c cell) (*forecast, error) {
	hour := time.Now().Unix() / 3600
	w.mu.Lock()
	if hour != w.hour {
		w.hour, w.cache = hour, map[cell]*forecast{}
	}
	f := w.cache[c]
	w.mu.Unlock()
	if f != nil {
		return f, nil
	}
	v, err, _ := w.inflight.Do(fmt.Sprint(hour, c), func() (any, error) {
		f, err := w.fetch(context.WithoutCancel(ctx), c)
		if err != nil {
			log.Printf("weather %s,%s: %v", c.latText(), c.lonText(), err)
			return nil, err
		}
		w.mu.Lock()
		if w.hour == hour {
			w.cache[c] = f
		}
		w.mu.Unlock()
		return f, nil
	})
	if err != nil {
		return nil, err
	}
	return v.(*forecast), nil
}

func (w *weather) fetch(ctx context.Context, c cell) (*forecast, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if w.qweather != nil {
		f, err := w.fromQWeather(ctx, c)
		if err == nil {
			return f, nil
		}
		log.Printf("qweather %s,%s: %v; trying Open-Meteo", c.latText(), c.lonText(), err)
	}
	return w.fromOpenMeteo(ctx, c)
}

func (w *weather) fromQWeather(ctx context.Context, c cell) (*forecast, error) {
	q := w.qweather
	// Hourly goes up to 168 h at 和风 (spec §2.9's 240 h is its daily range).
	var hourly struct {
		Code   string
		Hourly []struct{ FxTime, Temp, Icon, Wind360, WindSpeed, Precip string }
	}
	if err := getJSON(ctx, w.client, q.base+"/v7/weather/168h?lang=zh&unit=m&location="+c.lonText()+","+c.latText(), q.token(time.Now()), &hourly); err != nil {
		return nil, err
	}
	if hourly.Code != "200" {
		return nil, fmt.Errorf("code %s", hourly.Code)
	}
	// 和风 has no gusts and doesn't say which elevation its forecast is for: both from Open-Meteo
	// (its elevation is the cell's ground, from a DEM).
	om, err := w.fromOpenMeteo(ctx, c)
	if err != nil {
		log.Printf("open-meteo gusts %s,%s: %v", c.latText(), c.lonText(), err)
		om = &forecast{}
	}
	var elevation *float64
	for _, h := range om.hours {
		elevation = h.Elevation
		break
	}
	f := &forecast{source: "qweather", hours: map[int64]api.WeatherHour{}, warnings: []api.WeatherWarning{}}
	for _, h := range hourly.Hourly {
		t, err1 := time.Parse("2006-01-02T15:04Z07:00", h.FxTime)
		temp, err2 := strconv.ParseFloat(h.Temp, 64)
		wind, err3 := strconv.ParseFloat(h.WindSpeed, 64) // km/h
		precip, err4 := strconv.ParseFloat(h.Precip, 64)
		if err := cmp.Or(err1, err2, err3, err4); err != nil {
			return nil, fmt.Errorf("hourly %+v: %w", h, err)
		}
		gust := wind / 3.6 * gustFactor
		if h, ok := om.hours[t.Unix()/3600]; ok {
			gust = h.Gust
		}
		var dir *float64
		if d, err := strconv.ParseFloat(h.Wind360, 64); err == nil {
			dir = &d
		}
		icon, _ := strconv.Atoi(h.Icon)
		f.hours[t.Unix()/3600] = api.WeatherHour{
			Temp: temp, FeelsLike: windChill(temp, wind), Precip: precip, Gust: gust,
			Thunder: icon >= 302 && icon <= 304, // 雷阵雨, 强雷阵雨, 雷阵雨伴有冰雹
			Sky:     qweatherSky(icon), WindDir: dir, Elevation: elevation,
		}
	}
	var alerts struct {
		Alerts []struct {
			Id, Headline, Description string
			EventType                 struct{ Name string }
		}
	}
	// Missing warnings must not pass for "none": no answer from 和风 without them.
	if err := getJSON(ctx, w.client, q.base+"/weatheralert/v1/current/"+c.latText()+"/"+c.lonText()+"?lang=zh", q.token(time.Now()), &alerts); err != nil {
		return nil, fmt.Errorf("warnings: %w", err)
	}
	for _, a := range alerts.Alerts {
		f.warnings = append(f.warnings, api.WeatherWarning{Id: a.Id, Title: a.Headline, Text: a.Description,
			// 雷电, 雷雨大风, 雷暴大风; 强对流.
			Thunder: strings.Contains(a.EventType.Name, "雷") || strings.Contains(a.EventType.Name, "强对流")})
	}
	return f, nil
}

// qweatherSky is 和风's weather icon code as a sky: 1xx 晴/云 (150–153 by night), 3xx 雨, 4xx 雪, 5xx 雾/霾/沙尘.
func qweatherSky(icon int) api.WeatherHourSky {
	switch {
	case icon == 100 || icon == 150:
		return api.Clear
	case icon == 104:
		return api.Cloudy
	case icon >= 300 && icon < 400:
		return api.Rain
	case icon >= 400 && icon < 500:
		return api.Snow
	case icon >= 500 && icon < 600:
		return api.Fog
	}
	return api.Partly // 多云, 少云, 晴间多云, and the odd 热/冷/未知
}

// wmoSky is a WMO weather code (Open-Meteo) as a sky.
func wmoSky(code int) api.WeatherHourSky {
	switch {
	case code == 0:
		return api.Clear
	case code <= 2:
		return api.Partly
	case code == 3:
		return api.Cloudy
	case code == 45 || code == 48:
		return api.Fog
	case code >= 71 && code <= 77, code == 85, code == 86:
		return api.Snow
	}
	return api.Rain // drizzle, rain, showers, thunderstorms
}

// windChill is the 体感温度 in °C for air at t °C and wind at v km/h (the North American formula,
// defined at or below 10 °C and above 4.8 km/h; the air temperature otherwise).
func windChill(t, v float64) float64 {
	if t > 10 || v <= 4.8 {
		return t
	}
	p := math.Pow(v, 0.16)
	return 13.12 + 0.6215*t - 11.37*p + 0.3965*t*p
}

func (w *weather) fromOpenMeteo(ctx context.Context, c cell) (*forecast, error) {
	var om struct {
		Elevation *float64
		Hourly    struct {
			Time        []int64
			Temp        []*float64 `json:"temperature_2m"`
			FeelsLike   []*float64 `json:"apparent_temperature"`
			Precip      []*float64 `json:"precipitation"`
			Gust        []*float64 `json:"wind_gusts_10m"`
			WeatherCode []*int     `json:"weather_code"`
			WindDir     []*float64 `json:"wind_direction_10m"`
		}
	}
	q := url.Values{"latitude": {c.latText()}, "longitude": {c.lonText()}, "hourly": {"temperature_2m,apparent_temperature,precipitation,wind_gusts_10m,weather_code,wind_direction_10m"},
		"wind_speed_unit": {"ms"}, "timeformat": {"unixtime"}, "forecast_days": {"10"}}
	if err := getJSON(ctx, w.client, w.openMeteo+"/v1/forecast?"+q.Encode(), "", &om); err != nil {
		return nil, err
	}
	f := &forecast{source: "open-meteo", hours: map[int64]api.WeatherHour{}, warnings: []api.WeatherWarning{}}
	h := om.Hourly
	for i, t := range h.Time {
		if i >= len(h.Temp) || i >= len(h.FeelsLike) || i >= len(h.Precip) || i >= len(h.Gust) || i >= len(h.WeatherCode) ||
			h.Temp[i] == nil || h.FeelsLike[i] == nil || h.Precip[i] == nil || h.Gust[i] == nil || h.WeatherCode[i] == nil {
			continue
		}
		code := *h.WeatherCode[i]
		var dir *float64
		if i < len(h.WindDir) {
			dir = h.WindDir[i]
		}
		f.hours[t/3600] = api.WeatherHour{Temp: *h.Temp[i], FeelsLike: *h.FeelsLike[i], Precip: *h.Precip[i], Gust: *h.Gust[i],
			Thunder: code >= 95, Sky: wmoSky(code), WindDir: dir, Elevation: om.Elevation} // WMO 95, 96, 99: thunderstorm
	}
	return f, nil
}

// getJSON decodes a 200 answer into out; bearer, if set, goes in Authorization.
func getJSON(ctx context.Context, client *http.Client, u, bearer string, out any) error {
	r, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return err
	}
	if bearer != "" {
		r.Header.Set("Authorization", "Bearer "+bearer)
	}
	res, err := client.Do(r)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return fmt.Errorf("%s: %d", r.URL.Path, res.StatusCode)
	}
	return json.NewDecoder(res.Body).Decode(out)
}

// qweather holds the 和风 credentials (#37): a JWT signed with the project's Ed25519 key, not an API key.
type qweather struct {
	base      string // "https://<account API host>"
	projectID string
	keyID     string
	key       ed25519.PrivateKey
}

// loadQWeather reads the private key; nil without a host (Open-Meteo only).
func loadQWeather(host, projectID, keyID, keyPath string) (*qweather, error) {
	if host == "" {
		return nil, nil
	}
	b, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(b)
	if block == nil {
		return nil, fmt.Errorf("%s: no PEM block", keyPath)
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	key, ok := k.(ed25519.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("%s: not an Ed25519 key", keyPath)
	}
	if !strings.Contains(host, "://") {
		host = "https://" + host
	}
	return &qweather{base: host, projectID: projectID, keyID: keyID, key: key}, nil
}

// token is a JWT valid for 15 minutes; iat is back-dated 30 s against clock skew, as 和风 advises.
// Signing is cheap, so every request gets a fresh one.
func (q *qweather) token(now time.Time) string {
	enc := base64.RawURLEncoding
	head, _ := json.Marshal(map[string]string{"alg": "EdDSA", "kid": q.keyID})
	claims, _ := json.Marshal(map[string]any{"sub": q.projectID, "iat": now.Unix() - 30, "exp": now.Unix() + 900})
	s := enc.EncodeToString(head) + "." + enc.EncodeToString(claims)
	return s + "." + enc.EncodeToString(ed25519.Sign(q.key, []byte(s)))
}
