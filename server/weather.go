package main

// 天气 through the server (spec §2.9, ADR 0017): the forecast is Open-Meteo's for the place's 0.01° cell
// at its 地点海拔 (its DEM height when none is given), the official warnings 和风天气's. Neither stands in
// for the other; the app never sees the credentials.

import (
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
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"

	"stars-trail/server/api"
)

const (
	// §2.9: the forecast runs a week hour by hour from the current hour.
	weatherHours = 168
	// ADR 0017: each answer is cached for its own time, its entry expiring on its own.
	forecastTTL = 3 * time.Hour
	warningTTL  = time.Hour
	// §2.9: a day hike is 10–30 places, re-asked every 2 h while recording; 3000 is ~100 of those a day.
	// ponytail: one per request for now, cached or not; Open-Meteo's own weighted counting comes with
	// the 专业天气 work (#247).
	weatherRequestsPerDay = 3000
)

// cell is a 0.01° grid square (issue #11), in hundredths of a degree.
type cell struct{ lat, lon int }

// latText and lonText are the cell's corner as "33.96", as the providers are asked.
func (c cell) latText() string { return strconv.FormatFloat(float64(c.lat)/100, 'f', 2, 64) }
func (c cell) lonText() string { return strconv.FormatFloat(float64(c.lon)/100, 'f', 2, 64) }

// eleBand is 地点海拔 to the nearest 100 m: the band a forecast (and its cache entry) is keyed by.
func eleBand(ele float64) int { return int(math.Round(ele/100)) * 100 }

// forecastKey is one Open-Meteo answer: a 0.01° cell and the elevation band its temperatures are for.
// hasEle false means no elevation was given, so Open-Meteo picks the place's DEM height.
type forecastKey struct {
	cell    cell
	hasEle  bool
	eleBand int
}

// forecast is one key's answer: hours keyed by Unix hour (seconds / 3600).
type forecast struct {
	elevation *float64
	hours     map[int64]api.WeatherHour
}

// hoursFrom is the week of hours starting at now's hour, in order; fewer if the forecast stops.
func (f *forecast) hoursFrom(now time.Time) []api.WeatherHour {
	start := now.Unix() / 3600
	hours := make([]api.WeatherHour, 0, weatherHours)
	for i := int64(0); i < weatherHours; i++ {
		if h, ok := f.hours[start+i]; ok {
			hours = append(hours, h)
		}
	}
	return hours
}

// cacheEntry is one cached answer and when it stops being used.
type cacheEntry[T any] struct {
	v       T
	expires time.Time
}

type weather struct {
	qweather  *qweather // nil: not configured, so no warnings
	openMeteo string    // "https://api.open-meteo.com"
	client    *http.Client
	now       func() time.Time
	// ponytail: daily request quotas in memory, reset on restart (single instance, §3.2).
	devices, ips *limiter
	// Every answer is cached under its own key until its own expiry; expired entries go on write.
	mu        sync.Mutex
	forecasts map[forecastKey]cacheEntry[*forecast]
	warnings  map[cell]cacheEntry[[]api.WeatherWarning]
	inflight  singleflight.Group
}

func newWeather(q *qweather, openMeteo string, client *http.Client, requestsPerDay int64) *weather {
	return &weather{qweather: q, openMeteo: openMeteo, client: client, now: time.Now,
		devices: &limiter{max: requestsPerDay, period: 86400}, ips: &limiter{max: requestsPerDay * ipShare, period: 86400},
		forecasts: map[forecastKey]cacheEntry[*forecast]{}, warnings: map[cell]cacheEntry[[]api.WeatherWarning]{}}
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

func (s *server) GetWeather(ctx context.Context, req api.GetWeatherRequestObject) (api.GetWeatherResponseObject, error) {
	p := req.Params
	if !(p.Lat >= -90 && p.Lat <= 90 && p.Lon >= -180 && p.Lon <= 180) {
		return api.GetWeather400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	w := s.weather
	if w == nil {
		return api.GetWeather503JSONResponse{DataUnavailableJSONResponse: api.DataUnavailableJSONResponse{Error: api.ErrorCodeDataUnavailable}}, nil
	}
	// One per request, cached or not; the IP gets a looser cap (device IDs are rotatable).
	device, ip := quotaKeys(ctx, p.XDeviceId)
	if !w.devices.fits(device, 1) || !w.ips.fits(ip, 1) {
		return api.GetWeather429JSONResponse{Error: api.ErrorCodeDailyQuotaExceeded}, nil
	}
	w.devices.take(device, 1)
	w.ips.take(ip, 1)

	c := cell{int(math.Round(p.Lat * 100)), int(math.Round(p.Lon * 100))}
	key := forecastKey{cell: c}
	if p.Ele != nil {
		key.hasEle, key.eleBand = true, eleBand(*p.Ele)
	}
	res := api.Weather{
		Forecast: api.WeatherForecastFailed,
		Hours:    []api.WeatherHour{},
		Warnings: []api.WeatherWarning{},
		Sources:  []api.WeatherSources{},
	}
	if f, err := w.forecast(ctx, key); err == nil {
		res.Forecast = api.WeatherForecastOk
		res.Elevation = f.elevation
		res.Hours = f.hoursFrom(w.now())
		res.Sources = append(res.Sources, api.OpenMeteo)
	}
	warnings, failed := w.warningsFor(ctx, c)
	res.Warnings = warnings
	if failed {
		res.WarningsFailed = &failed
	}
	if len(warnings) > 0 {
		res.Sources = append(res.Sources, api.Qweather)
	}
	return api.GetWeather200JSONResponse(res), nil
}

// forecast is key's forecast from cache, or fetched once however many requests want it at once.
func (w *weather) forecast(ctx context.Context, key forecastKey) (*forecast, error) {
	if f, ok := w.cachedForecast(key); ok {
		return f, nil
	}
	v, err, _ := w.inflight.Do(fmt.Sprint("forecast ", key), func() (any, error) {
		f, err := w.fromOpenMeteo(context.WithoutCancel(ctx), key)
		if err != nil {
			log.Printf("open-meteo %s,%s: %v", key.cell.latText(), key.cell.lonText(), err)
			return nil, err
		}
		w.mu.Lock()
		w.purgeForecasts()
		w.forecasts[key] = cacheEntry[*forecast]{v: f, expires: w.now().Add(forecastTTL)}
		w.mu.Unlock()
		return f, nil
	})
	if err != nil {
		return nil, err
	}
	return v.(*forecast), nil
}

func (w *weather) cachedForecast(key forecastKey) (*forecast, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	e, ok := w.forecasts[key]
	if !ok || w.now().After(e.expires) {
		return nil, false
	}
	return e.v, true
}

// purgeWarnings drops the warnings entries that have expired.
func (w *weather) purgeWarnings() {
	now := w.now()
	for c, e := range w.warnings {
		if now.After(e.expires) {
			delete(w.warnings, c)
		}
	}
}

// warningsFor is c's official warnings, from cache or fetched once; failed is true when 和风 can't be asked.
func (w *weather) warningsFor(ctx context.Context, c cell) (warnings []api.WeatherWarning, failed bool) {
	if w.qweather == nil {
		return []api.WeatherWarning{}, true
	}
	if a, ok := w.cachedWarnings(c); ok {
		return a, false
	}
	v, err, _ := w.inflight.Do(fmt.Sprint("warnings ", c), func() (any, error) {
		a, err := w.fromQWeather(context.WithoutCancel(ctx), c)
		if err != nil {
			log.Printf("qweather warnings %s,%s: %v", c.latText(), c.lonText(), err)
			return nil, err
		}
		w.mu.Lock()
		w.purgeWarnings()
		w.warnings[c] = cacheEntry[[]api.WeatherWarning]{v: a, expires: w.now().Add(warningTTL)}
		w.mu.Unlock()
		return a, nil
	})
	if err != nil {
		return []api.WeatherWarning{}, true
	}
	return v.([]api.WeatherWarning), false
}

func (w *weather) cachedWarnings(c cell) ([]api.WeatherWarning, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	e, ok := w.warnings[c]
	if !ok || w.now().After(e.expires) {
		return nil, false
	}
	return e.v, true
}

func (w *weather) purgeForecasts() {
	now := w.now()
	for k, e := range w.forecasts {
		if now.After(e.expires) {
			delete(w.forecasts, k)
		}
	}
}

// fromOpenMeteo is the basic forecast for key's cell and elevation band: temperatures corrected to the
// place's 地点海拔 (elevation=, ele rounded to 100 m), or Open-Meteo's 90 m DEM height when none is given.
func (w *weather) fromOpenMeteo(ctx context.Context, key forecastKey) (*forecast, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	c := key.cell
	q := url.Values{
		"latitude":        {c.latText()},
		"longitude":       {c.lonText()},
		"hourly":          {"temperature_2m,apparent_temperature,precipitation,wind_gusts_10m,weather_code,wind_direction_10m"},
		"wind_speed_unit": {"ms"},
		"timeformat":      {"unixtime"},
		// A week from the current hour can straddle eight calendar days.
		"forecast_days": {"8"},
	}
	if key.hasEle {
		q.Set("elevation", strconv.Itoa(key.eleBand))
	}
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
	if err := getJSON(ctx, w.client, w.openMeteo+"/v1/forecast?"+q.Encode(), "", &om); err != nil {
		return nil, err
	}
	f := &forecast{elevation: om.Elevation, hours: map[int64]api.WeatherHour{}}
	if key.hasEle {
		// The temperatures are for the elevation we asked for; say so even if Open-Meteo moves it.
		ele := float64(key.eleBand)
		f.elevation = &ele
	}
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
		f.hours[t/3600] = api.WeatherHour{Time: t, Temp: *h.Temp[i], FeelsLike: *h.FeelsLike[i], Precip: *h.Precip[i], Gust: *h.Gust[i],
			Thunder: code >= 95, Sky: wmoSky(code), WindDir: dir} // WMO 95, 96, 99: thunderstorm
	}
	// A 200 without a single usable hour isn't an answer ("ok: hours are there"), so treat it as failed.
	if len(f.hours) == 0 {
		return nil, fmt.Errorf("open-meteo %s,%s: no hourly data", c.latText(), c.lonText())
	}
	return f, nil
}

// fromQWeather asks 和风 for the official warnings in force at c (ADR 0017: warnings only).
func (w *weather) fromQWeather(ctx context.Context, c cell) ([]api.WeatherWarning, error) {
	q := w.qweather
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	var alerts struct {
		Alerts []struct {
			Id, Headline, Description string
			SenderName                string `json:"senderName"`
			IssuedTime                string `json:"issuedTime"`
			EventType                 struct{ Name string }
		}
	}
	// Missing warnings must not pass for "none": no answer from 和风 without them.
	if err := getJSON(ctx, w.client, q.base+"/weatheralert/v1/current/"+c.latText()+"/"+c.lonText()+"?lang=zh", q.token(w.now()), &alerts); err != nil {
		return nil, fmt.Errorf("warnings: %w", err)
	}
	warnings := []api.WeatherWarning{}
	for _, a := range alerts.Alerts {
		warn := api.WeatherWarning{Id: a.Id, Title: a.Headline, Text: a.Description,
			// 雷电, 雷雨大风, 雷暴大风; 强对流.
			Thunder: strings.Contains(a.EventType.Name, "雷") || strings.Contains(a.EventType.Name, "强对流")}
		if a.SenderName != "" {
			sender := a.SenderName
			warn.Sender = &sender
		}
		// 和风's issuedTime has no seconds ("2026-09-28T09:30+08:00"), so not RFC 3339.
		if t, err := time.Parse("2006-01-02T15:04Z07:00", a.IssuedTime); err == nil {
			warn.IssuedAt = &t
		}
		warnings = append(warnings, warn)
	}
	return warnings, nil
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

// loadQWeather reads the private key; nil without a host (Open-Meteo only, no warnings).
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
