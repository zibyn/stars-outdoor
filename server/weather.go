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
	"errors"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"sort"
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

	// ADR 0017 (#247): Open-Meteo counts a call as 1, plus one more per ten variables past ten. Counts
	// are kept in tenths of a call so 4.6 needs no float.
	weightUnit  = 10
	basicWeight = 10 // the basic forecast's six variables: one call
	// #254: the 廓线 request's ~46 variables (four × ten pressure levels, the cloud layers, precipitation,
	// the 0°C level and CAPE) count 4.6 calls.
	profileWeight = 46
	// ponytail: Open-Meteo doesn't document the ensemble's counting; worst case 变量 × 成员 / 10 =
	// 3 × 51 / 10 ≈ 15.3 calls. Check the daily log once live and correct this. #255 charges it.
	ensembleWeight = 153

	// #247: the server stops calling Open-Meteo before the free plan's 10 000 a day and 300 000 a month,
	// and logs WARN once a day passes 7000. A device gets 1000 weighted calls a day, its IP ten times
	// that (X-Device-Id is rotatable); 1000 is ~4 day hikes. Days and months are UTC.
	openMeteoPerDay     = 9000
	openMeteoPerMonth   = 280000
	openMeteoWarnPerDay = 7000
	deviceCallsPerDay   = 1000
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

// profileCell is a 0.1° grid square, in tenths of a degree: the 廓线 request's own cell (#254).
type profileCell struct{ lat, lon int }

// latText and lonText are the cell's corner as "34.0", as Open-Meteo is asked for it.
func (c profileCell) latText() string { return strconv.FormatFloat(float64(c.lat)/10, 'f', 1, 64) }
func (c profileCell) lonText() string { return strconv.FormatFloat(float64(c.lon)/10, 'f', 1, 64) }

// profileCellOf rounds the place's coordinates to the 0.1° cell the 廓线 is fetched and cached by
// (#254). Rounding from the raw coordinates, not the 0.01° cell already rounded, matters near a 0.05°
// boundary: 33.947 belongs to 33.9, though its 0.01° cell 33.95 rounds to 34.0.
func profileCellOf(lat, lon float64) profileCell {
	return profileCell{int(math.Round(lat * 10)), int(math.Round(lon * 10))}
}

// pressureLevels are the 廓线 request's pressure levels, hPa (#243), low to high.
var pressureLevels = []int{1000, 975, 950, 925, 900, 850, 800, 700, 600, 500}

// profileVars are the 廓线 request's hourly variables: four at each pressure level (wind_speed is for
// 云海, #255), then the ground's cloud layers, precipitation, 0°C level and CAPE. 46 of them, which is
// why profileWeight is 4.6 calls (#247).
func profileVars() []string {
	vars := make([]string, 0, 46)
	for _, level := range pressureLevels {
		for _, v := range []string{"temperature", "relative_humidity", "geopotential_height", "wind_speed"} {
			vars = append(vars, fmt.Sprintf("%s_%dhPa", v, level))
		}
	}
	return append(vars, "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high", "precipitation",
		"freezing_level_height", "cape")
}

// cloudLevel is one pressure level of a 廓线 hour: what the 云海 rule runs on (#255). Wind rides along
// with the 垂直剖面's height, temperature and humidity.
type cloudLevel struct {
	hpa    int     // the pressure level, hPa
	height float64 // geopotential height, metres above sea level
	temp   float64 // °C
	rh     float64 // relative humidity, %
	wind   float64 // m/s
}

// profileHour is one hour's detail fields (#254); a field is nil when the 廓线 answer didn't have it.
type profileHour struct {
	cloudLow, cloudMid, cloudHigh *float64
	precipitation                 *float64
	freezingLevel                 *float64
	thunderPotential              *api.WeatherHourThunderPotential
	// levels are the pressure levels above the cell's ground, bottom up (#254; wind is for #255).
	levels []cloudLevel
}

// profile is one 0.1° cell's 廓线 answer (#254): the cell's ground height and its hours, keyed by Unix hour.
type profile struct {
	groundElevation *float64
	hours           map[int64]profileHour
}

// ponytail: #255's thresholds are initial values, to be calibrated against real observations after
// launch (the sources are #240 and #243).
const (
	// A place this much above the cell's ground is a summit; below it nothing is given, not "低".
	cloudSeaMinRelief = 200 // m
	// Slingo: the RH above which a level counts as fully cloud. The bands split at the 700 hPa level
	// itself: 0.65 from 700 up to 500 (the free troposphere), 0.80 below it (#240).
	cloudSeaHcBand = 700 // hPa
	cloudSeaHcLow  = 0.80
	cloudSeaHcHigh = 0.65
	// The 山下 band is the levels at or below this far under the place; the 高空 band starts this far
	// above it.
	cloudSeaBelowDepth = 100 // m
	cloudSeaAboveStart = 300 // m
	// 分档: 山下 ≥ 0.4, 山顶 < 0.1 and 高空 < 0.4 with a stable layer make 高; 山下 ≤ 0.1, 山顶 ≥ 0.4 (the
	// place is in cloud), rain, or a place wind ≥ 10 m/s make 低; the rest is 中.
	cloudSeaCBelowHigh  = 0.4
	cloudSeaCBelowLow   = 0.1
	cloudSeaCTopHigh    = 0.1
	cloudSeaCTopInCloud = 0.4
	cloudSeaCAboveHigh  = 0.4
	cloudSeaWindMax     = 10.0 // m/s at the place
	cloudSeaLapseStable = 0.3  // °C/100 m or less (an inversion is below 0): a stable layer
	cloudSeaPrecipMin   = 0.1  // mm/h: rain rules 云海 out
)

// cloudSeaInput is one hour's 云海 inputs (#255): the 廓线 levels, the cell's ground, the place's
// 地点海拔, and the ground's cloud and precipitation fields.
type cloudSeaInput struct {
	levels        []cloudLevel
	ground        float64 // the cell's ground, m
	place         float64 // 地点海拔, m
	cloudMid      float64 // mid cloud cover, %
	cloudHigh     float64 // high cloud cover, %
	precipitation float64 // mm/h
}

// cloudSeaVerdict is one hour's 云海 answer (#255): the 档位 and, when there is cloud below, its top.
type cloudSeaVerdict struct {
	tier   api.WeatherHourCloudSea
	top    float64 // metres above sea level
	hasTop bool
}

// cloudSea decides one hour's 云海 and cloud top (#243 §4, #255): #240's 三段法 over the 廓线, the cell's
// ground and the place's 地点海拔. ok is false — no 云海 at all, not "低" — when the place isn't 200 m
// above the cell's ground or is above the 500 hPa level, and when there are no levels.
func cloudSea(in cloudSeaInput) (cloudSeaVerdict, bool) {
	if len(in.levels) == 0 || in.place < in.ground+cloudSeaMinRelief {
		return cloudSeaVerdict{}, false
	}
	// The 廓线 stops at 500 hPa (about 5500 m); the place must stay below it, hour by hour.
	if in.place > in.levels[len(in.levels)-1].height {
		return cloudSeaVerdict{}, false
	}
	cBelow, top := 0.0, 0.0
	cAbove := math.Max(in.cloudMid, in.cloudHigh) / 100
	for _, l := range in.levels {
		n := cloudAmount(l.rh, l.hpa)
		if l.height <= in.place-cloudSeaBelowDepth {
			cBelow = math.Max(cBelow, n)
			if n >= cloudSeaCBelowLow {
				top = l.height // levels are bottom up, so the last one is the highest
			}
		}
		if l.height >= in.place+cloudSeaAboveStart {
			cAbove = math.Max(cAbove, n)
		}
	}
	cTop := cloudAmount(lerpAt(in.levels, in.place, func(l cloudLevel) float64 { return l.rh }),
		lowerHpa(in.levels, in.place))
	wind := lerpAt(in.levels, in.place, func(l cloudLevel) float64 { return l.wind })

	var tier api.WeatherHourCloudSea
	switch {
	case cBelow <= cloudSeaCBelowLow || cTop >= cloudSeaCTopInCloud ||
		in.precipitation >= cloudSeaPrecipMin || wind >= cloudSeaWindMax:
		tier = api.WeatherHourCloudSeaLow
	case cBelow >= cloudSeaCBelowHigh && cTop < cloudSeaCTopHigh && cAbove < cloudSeaCAboveHigh &&
		stableAt(in.levels, in.place):
		tier = api.WeatherHourCloudSeaHigh
	default:
		tier = api.WeatherHourCloudSeaMedium
	}
	return cloudSeaVerdict{tier: tier, top: top, hasTop: cBelow >= cloudSeaCBelowLow}, true
}

// cloudAmount is #240's Slingo curve: N = clamp((RH − Hc)/(1 − Hc), 0, 1)², RH a fraction and Hc from
// the level's own band (cloudSeaHcBand).
func cloudAmount(rhPercent float64, hpa int) float64 {
	hc := cloudSeaHcLow
	if hpa <= cloudSeaHcBand {
		hc = cloudSeaHcHigh
	}
	n := (rhPercent/100 - hc) / (1 - hc)
	return math.Pow(math.Max(0, math.Min(1, n)), 2)
}

// lerpAt is the levels' value at height, linearly between the two bracketing levels; at or beyond the
// ends it clamps to that level.
func lerpAt(levels []cloudLevel, height float64, value func(cloudLevel) float64) float64 {
	if height <= levels[0].height {
		return value(levels[0])
	}
	last := levels[len(levels)-1]
	if height >= last.height {
		return value(last)
	}
	for i := 1; i < len(levels); i++ {
		if levels[i].height >= height {
			a, b := levels[i-1], levels[i]
			f := (height - a.height) / (b.height - a.height)
			return value(a) + f*(value(b)-value(a))
		}
	}
	return value(last)
}

// lowerHpa is the pressure of the highest level at or below height, for cloudAmount's Hc; the lowest
// level's when height is below them all.
func lowerHpa(levels []cloudLevel, height float64) int {
	hpa := levels[0].hpa
	for _, l := range levels {
		if l.height <= height {
			hpa = l.hpa
		}
	}
	return hpa
}

// stableAt is #240's stable layer: the lapse rate between the levels just below and just above the place
// is ≤ 0.3 °C/100 m; an inversion (a negative lapse) counts. False when the place isn't bracketed.
func stableAt(levels []cloudLevel, place float64) bool {
	var below, above *cloudLevel
	for i := range levels {
		l := &levels[i]
		if l.height <= place {
			if below == nil || l.height > below.height {
				below = l
			}
		} else if above == nil || l.height < above.height {
			above = l
		}
	}
	if below == nil || above == nil {
		return false
	}
	return (below.temp-above.temp)/(above.height-below.height)*100 <= cloudSeaLapseStable
}

// cacheEntry is one cached answer and when it stops being used.
type cacheEntry[T any] struct {
	v       T
	expires time.Time
}

// meteoBudget is the server's own Open-Meteo allowance (#247, ADR 0017), in tenths of a call. It lives
// in memory, so a restart clears it, which can only ever under-count (single instance, §3.2), and the
// day and month are UTC. Only calls actually sent are charged; the first request after a UTC day changes
// writes the finished day's total, WARN once it passed the buy-Standard line.
type meteoBudget struct {
	mu sync.Mutex
	// Tenths of a call throughout: the caps and the running totals.
	dayMax, monthMax, warnMax int64
	logf                      func(format string, args ...any)
	day, month                int64 // charged today, this month
	dayKey, monthKey          string
}

func newMeteoBudget() *meteoBudget {
	return &meteoBudget{dayMax: openMeteoPerDay * weightUnit, monthMax: openMeteoPerMonth * weightUnit,
		warnMax: openMeteoWarnPerDay * weightUnit, logf: log.Printf}
}

// roll starts a new UTC day or month when now has moved past the last one, writing the finished day's
// total as it goes. Called by every request, so a day that closed over a cache hit is still logged.
func (b *meteoBudget) roll(now time.Time) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.rollLocked(now)
}

func (b *meteoBudget) rollLocked(now time.Time) {
	u := now.UTC()
	day, month := u.Format("2006-01-02"), u.Format("2006-01")
	if b.dayKey == "" {
		b.dayKey, b.monthKey = day, month
		return
	}
	if month != b.monthKey {
		b.month, b.monthKey = 0, month
	}
	if day != b.dayKey {
		b.summarize(b.dayKey, b.day)
		b.day, b.dayKey = 0, day
	}
}

// reserve charges n tenths to both allowances if they still have room, and says whether it did. The
// check and the charge share one lock, so two calls can't both take the last of the allowance.
func (b *meteoBudget) reserve(n int64, now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.rollLocked(now)
	if b.day+n > b.dayMax || b.month+n > b.monthMax {
		return false
	}
	b.day += n
	b.month += n
	return true
}

// summarize writes a finished UTC day's total, on one line (#247).
func (b *meteoBudget) summarize(day string, tenths int64) {
	calls := float64(tenths) / weightUnit
	if tenths > b.warnMax {
		b.logf("WARN weather: %s: %.1f Open-Meteo calls, past %d; buy Standard (ADR 0017)", day, calls, b.warnMax/weightUnit)
		return
	}
	b.logf("weather: %s: %.1f Open-Meteo calls", day, calls)
}

type weather struct {
	qweather  *qweather // nil: not configured, so no warnings
	openMeteo string    // "https://api.open-meteo.com"
	client    *http.Client
	now       func() time.Time
	// ponytail: the device and IP quotas are in memory, reset on restart (single instance, §3.2).
	devices, ips *limiter
	// The server's own Open-Meteo allowance, in tenths of a call (#247).
	budget *meteoBudget
	// Every answer is cached under its own key until its own expiry; expired entries go on write.
	mu        sync.Mutex
	forecasts map[forecastKey]cacheEntry[*forecast]
	profiles  map[profileCell]cacheEntry[*profile]
	warnings  map[cell]cacheEntry[[]api.WeatherWarning]
	inflight  singleflight.Group
}

// newWeather's callsPerDevicePerDay is in weighted calls (#247): the device's own daily budget.
func newWeather(q *qweather, openMeteo string, client *http.Client, callsPerDevicePerDay int64) *weather {
	return &weather{qweather: q, openMeteo: openMeteo, client: client, now: time.Now,
		devices:   &limiter{max: callsPerDevicePerDay * weightUnit, period: 86400},
		ips:       &limiter{max: callsPerDevicePerDay * weightUnit * ipShare, period: 86400},
		budget:    newMeteoBudget(),
		forecasts: map[forecastKey]cacheEntry[*forecast]{}, profiles: map[profileCell]cacheEntry[*profile]{},
		warnings: map[cell]cacheEntry[[]api.WeatherWarning]{}}
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
	// A new UTC day or month starts here, even when a cache answers the rest, so the finished day's line
	// is written by the first request after the change (#247).
	w.budget.roll(w.now())
	// #247: the device is charged what the request would cost Open-Meteo, cached or not; the IP gets a
	// looser cap (device IDs are rotatable). A detail request adds the 廓线's 4.6 (#254); the ensemble
	// joins with #255.
	detail := p.Detail != nil && *p.Detail
	weight := int64(basicWeight)
	if detail {
		weight += profileWeight
	}
	device, ip := quotaKeys(ctx, p.XDeviceId)
	if !w.devices.fits(device, weight) || !w.ips.fits(ip, weight) {
		return api.GetWeather429JSONResponse{Error: api.ErrorCodeDailyQuotaExceeded}, nil
	}
	w.devices.take(device, weight)
	w.ips.take(ip, weight)

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
	f, err := w.forecast(ctx, key)
	switch {
	case err == nil:
		res.Forecast = api.WeatherForecastOk
		res.Elevation = f.elevation
		res.Hours = f.hoursFrom(w.now())
		res.Sources = append(res.Sources, api.OpenMeteo)
	case errors.Is(err, errMeteoQuota):
		res.Forecast = api.WeatherForecastQuotaExhausted
	}
	// #254: detail adds the 廓线's fields. Its own failure, or a spent allowance, leaves the basic
	// forecast as it is (forecast: ok), with the detail fields absent.
	if detail && res.Forecast == api.WeatherForecastOk {
		if prof, err := w.profileFor(ctx, p.Lat, p.Lon); err == nil {
			res.GroundElevation = prof.groundElevation
			// #255: 云海 goes by the place's 地点海拔 — the request's ele, or the basic answer's elevation
			// (Open-Meteo's DEM height at the place) when none was given.
			place := p.Ele
			if place == nil {
				place = res.Elevation
			}
			mergeProfile(res.Hours, prof, place)
		}
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

// errMeteoQuota is the server's own Open-Meteo allowance being spent (#247): no call goes out, and the
// answer says quota_exhausted.
var errMeteoQuota = errors.New("open-meteo allowance spent")

// forecast is key's forecast from cache, or fetched once however many requests want it at once.
func (w *weather) forecast(ctx context.Context, key forecastKey) (*forecast, error) {
	if f, ok := w.cachedForecast(key); ok {
		return f, nil
	}
	v, err, _ := w.inflight.Do(fmt.Sprint("forecast ", key), func() (any, error) {
		f, err := w.fromOpenMeteo(context.WithoutCancel(ctx), key)
		if err != nil {
			// A spent allowance is an expected answer, not an Open-Meteo failure to log.
			if !errors.Is(err, errMeteoQuota) {
				log.Printf("open-meteo %s,%s: %v", key.cell.latText(), key.cell.lonText(), err)
			}
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

// profileFor is the place's 0.1° cell 廓线 from cache, or fetched once however many requests want it at once.
func (w *weather) profileFor(ctx context.Context, lat, lon float64) (*profile, error) {
	key := profileCellOf(lat, lon)
	if p, ok := w.cachedProfile(key); ok {
		return p, nil
	}
	v, err, _ := w.inflight.Do(fmt.Sprint("profile ", key), func() (any, error) {
		p, err := w.fromOpenMeteoProfile(context.WithoutCancel(ctx), key)
		if err != nil {
			// A spent allowance is an expected answer, not an Open-Meteo failure to log.
			if !errors.Is(err, errMeteoQuota) {
				log.Printf("open-meteo profile %s,%s: %v", key.latText(), key.lonText(), err)
			}
			return nil, err
		}
		w.mu.Lock()
		w.purgeProfiles()
		w.profiles[key] = cacheEntry[*profile]{v: p, expires: w.now().Add(forecastTTL)}
		w.mu.Unlock()
		return p, nil
	})
	if err != nil {
		return nil, err
	}
	return v.(*profile), nil
}

func (w *weather) cachedProfile(key profileCell) (*profile, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	e, ok := w.profiles[key]
	if !ok || w.now().After(e.expires) {
		return nil, false
	}
	return e.v, true
}

func (w *weather) purgeProfiles() {
	now := w.now()
	for k, e := range w.profiles {
		if now.After(e.expires) {
			delete(w.profiles, k)
		}
	}
}

// mergeProfile adds p's detail fields to the hours that have them (#254), and the hours' 云海 and cloud
// top for the place's 地点海拔 (#255). place is nil when neither the request nor the basic answer had an
// elevation.
func mergeProfile(hours []api.WeatherHour, p *profile, place *float64) {
	for i := range hours {
		ph, ok := p.hours[hours[i].Time/3600]
		if !ok {
			continue
		}
		hours[i].CloudLow, hours[i].CloudMid, hours[i].CloudHigh = ph.cloudLow, ph.cloudMid, ph.cloudHigh
		hours[i].FreezingLevel = ph.freezingLevel
		hours[i].ThunderPotential = ph.thunderPotential
		if len(ph.levels) > 0 {
			levels := make([]api.WeatherLevel, len(ph.levels))
			for j, l := range ph.levels {
				levels[j] = api.WeatherLevel{Height: l.height, Temp: l.temp, Rh: l.rh}
			}
			hours[i].Profile = &levels
		}
		if place == nil || p.groundElevation == nil {
			continue
		}
		v, ok := cloudSea(cloudSeaInput{levels: ph.levels, ground: *p.groundElevation, place: *place,
			cloudMid: deref(ph.cloudMid), cloudHigh: deref(ph.cloudHigh), precipitation: deref(ph.precipitation)})
		if !ok {
			continue
		}
		tier := v.tier
		hours[i].CloudSea = &tier
		if v.hasTop {
			top := v.top
			hours[i].CloudTop = &top
		}
	}
}

// fromOpenMeteoProfile asks for the 廓线 of key's 0.1° cell (#254): the pressure levels and the ground's
// cloud layers, precipitation, 0°C level and CAPE. elevation=nan asks for them at the cell's own ground,
// which the answer's elevation then is (ADR 0017), so its profile starts at the cell's ground.
func (w *weather) fromOpenMeteoProfile(ctx context.Context, key profileCell) (*profile, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	q := url.Values{
		"latitude":        {key.latText()},
		"longitude":       {key.lonText()},
		"hourly":          {strings.Join(profileVars(), ",")},
		"wind_speed_unit": {"ms"},
		"elevation":       {"nan"},
		"timeformat":      {"unixtime"},
		"forecast_days":   {"8"},
	}
	var om struct {
		Elevation *float64
		Hourly    map[string][]*float64
	}
	if err := w.getOpenMeteo(ctx, w.openMeteo+"/v1/forecast?"+q.Encode(), profileWeight, &om); err != nil {
		return nil, err
	}
	if om.Elevation == nil {
		return nil, fmt.Errorf("open-meteo profile %s,%s: no ground elevation", key.latText(), key.lonText())
	}
	p := &profile{groundElevation: om.Elevation, hours: map[int64]profileHour{}}
	// at is the hour's value of a variable, or nil when the answer doesn't carry it.
	at := func(name string, i int) *float64 {
		v := om.Hourly[name]
		if i >= len(v) {
			return nil
		}
		return v[i]
	}
	for i, t := range om.Hourly["time"] {
		if t == nil {
			continue
		}
		ph := profileHour{cloudLow: at("cloud_cover_low", i), cloudMid: at("cloud_cover_mid", i),
			cloudHigh: at("cloud_cover_high", i), precipitation: at("precipitation", i),
			freezingLevel: at("freezing_level_height", i)}
		if cape := at("cape", i); cape != nil {
			tp := thunderPotential(*cape)
			ph.thunderPotential = &tp
		}
		for _, level := range pressureLevels {
			height := at(fmt.Sprintf("geopotential_height_%dhPa", level), i)
			temp := at(fmt.Sprintf("temperature_%dhPa", level), i)
			rh := at(fmt.Sprintf("relative_humidity_%dhPa", level), i)
			// Only the levels above the cell's ground are a 垂直剖面 of the place (#254).
			if height == nil || temp == nil || rh == nil || *height <= *om.Elevation {
				continue
			}
			// Wind is for 云海 (#255); 0 keeps a level the answer had no wind for in the 垂直剖面.
			ph.levels = append(ph.levels, cloudLevel{hpa: level, height: *height, temp: *temp, rh: *rh,
				wind: deref(at(fmt.Sprintf("wind_speed_%dhPa", level), i))})
		}
		// The levels come back in the order asked, but sort to be sure they are bottom up.
		sort.Slice(ph.levels, func(a, b int) bool { return ph.levels[a].height < ph.levels[b].height })
		p.hours[int64(*t)/3600] = ph
	}
	if len(p.hours) == 0 {
		return nil, fmt.Errorf("open-meteo profile %s,%s: no hourly data", key.latText(), key.lonText())
	}
	return p, nil
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
	if err := w.getOpenMeteo(ctx, w.openMeteo+"/v1/forecast?"+q.Encode(), basicWeight, &om); err != nil {
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

// ponytail: #248's initial CAPE thresholds, J/kg; a guess until real days have been seen.
const (
	capeMediumFrom = 300
	capeHighFrom   = 1000
)

// thunderPotential is CAPE as 雷暴潜势: low below 300 J/kg, high above 1000, medium in between (#248).
func thunderPotential(cape float64) api.WeatherHourThunderPotential {
	switch {
	case cape < capeMediumFrom:
		return api.WeatherHourThunderPotentialLow
	case cape <= capeHighFrom:
		return api.WeatherHourThunderPotentialMedium
	}
	return api.WeatherHourThunderPotentialHigh
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

// getOpenMeteo asks Open-Meteo if the server's own allowance still has room for the call's weight (#247),
// and charges it; errMeteoQuota means the call was never sent.
func (w *weather) getOpenMeteo(ctx context.Context, u string, weight int64, out any) error {
	if !w.budget.reserve(weight, w.now()) {
		return errMeteoQuota
	}
	return getJSON(ctx, w.client, u, "", out)
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
