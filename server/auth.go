package main

// 登录 (spec §2.12): +86 numbers only, with a code texted by 阿里云号码认证 · 短信认证, which also keeps
// and checks the code, so the server stores none. A login hands out a random bearer token; the database
// keeps only its SHA-256, until logout.

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"stars-outdoor/server/api"
)

const (
	loginTries       = 10 // login tries per number per hour, right or wrong
	phoneCodesPerDay = 10
	ipCodesPerDay    = 30 // codes cost money: caps texting strangers' numbers from one place
)

var mainlandMobile = regexp.MustCompile(`^1[3-9][0-9]{9}$`)

const usersSchema = `
CREATE TABLE IF NOT EXISTS users (
	id bigserial PRIMARY KEY,
	phone text NOT NULL UNIQUE,
	created_at timestamptz NOT NULL DEFAULT now()
);
-- 昵称 (nickname.go); NULL only until fillNicknames, on the start that adds the column.
ALTER TABLE users ADD COLUMN IF NOT EXISTS nickname text;
-- 头像 (avatar.go): an image id, NULL for none.
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar text;
CREATE TABLE IF NOT EXISTS sessions (
	token_hash bytea PRIMARY KEY,
	user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
	created_at timestamptz NOT NULL DEFAULT now()
);`

type user struct {
	id       int64
	phone    string
	nickname string
	avatar   *string
}

// userStore keeps accounts and sessions; pgUsers in production, in memory in tests.
type userStore interface {
	// login makes phone's account if it has none (with a [defaultNickname]) and opens a session for the token's hash.
	login(ctx context.Context, phone string, hash []byte) error
	user(ctx context.Context, hash []byte) (user, bool, error)
	logout(ctx context.Context, hash []byte) error
	setNickname(ctx context.Context, id int64, name string) error
	// setAvatar sets id's 头像 (nil: none) and returns the one it had.
	setAvatar(ctx context.Context, id int64, avatar *string) (old *string, err error)
}

type pgUsers struct{ db *pgxpool.Pool }

func (p pgUsers) login(ctx context.Context, phone string, hash []byte) error {
	_, err := p.db.Exec(ctx, `WITH u AS (
		INSERT INTO users (phone, nickname) VALUES ($1, $3) ON CONFLICT (phone) DO UPDATE SET phone = excluded.phone RETURNING id)
		INSERT INTO sessions (token_hash, user_id) SELECT $2, id FROM u`, phone, hash, defaultNickname())
	return err
}

func (p pgUsers) setNickname(ctx context.Context, id int64, name string) error {
	_, err := p.db.Exec(ctx, "UPDATE users SET nickname = $2 WHERE id = $1", id, name)
	return err
}

func (p pgUsers) setAvatar(ctx context.Context, id int64, avatar *string) (old *string, err error) {
	err = p.db.QueryRow(ctx, `UPDATE users u SET avatar = $2 FROM (SELECT avatar FROM users WHERE id = $1 FOR UPDATE) o
		WHERE u.id = $1 RETURNING o.avatar`, id, avatar).Scan(&old)
	return old, err
}

func (p pgUsers) user(ctx context.Context, hash []byte) (u user, ok bool, err error) {
	err = p.db.QueryRow(ctx, "SELECT u.id, u.phone, u.nickname, u.avatar FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = $1", hash).Scan(&u.id, &u.phone, &u.nickname, &u.avatar)
	if errors.Is(err, pgx.ErrNoRows) {
		return u, false, nil
	}
	return u, err == nil, err
}

func (p pgUsers) logout(ctx context.Context, hash []byte) error {
	_, err := p.db.Exec(ctx, "DELETE FROM sessions WHERE token_hash = $1", hash)
	return err
}

type accounts struct {
	sms                        *aliyunSMS
	users                      userStore
	ipCodes, phoneCodes, tries *limiter
	// testLogins (TEST_LOGINS, LAN test server only): numbers that log in with a fixed code, no text sent.
	testLogins map[string]string
}

// parseTestLogins reads TEST_LOGINS: "phone:code,phone:code".
func parseTestLogins(s string) map[string]string {
	m := map[string]string{}
	for _, kv := range strings.Split(s, ",") {
		if phone, code, ok := strings.Cut(strings.TrimSpace(kv), ":"); ok {
			m[phone] = code
		}
	}
	return m
}

func newAccounts(sms *aliyunSMS, users userStore) *accounts {
	return &accounts{sms: sms, users: users,
		ipCodes:    &limiter{max: ipCodesPerDay, period: 86400},
		phoneCodes: &limiter{max: phoneCodesPerDay, period: 86400},
		tries:      &limiter{max: loginTries, period: 3600}}
}

var smsUnavailable = api.SmsUnavailableJSONResponse{Error: api.ErrorCodeSmsUnavailable}

func (s *server) PostAuthCode(ctx context.Context, req api.PostAuthCodeRequestObject) (api.PostAuthCodeResponseObject, error) {
	phone := req.Body.Phone
	if !mainlandMobile.MatchString(phone) {
		return api.PostAuthCode400JSONResponse{Error: api.ErrorCodeInvalidPhone}, nil
	}
	a := s.accounts
	if _, ok := a.testLogins[phone]; ok {
		return api.PostAuthCode204Response{}, nil
	}
	if !a.sms.configured() {
		log.Printf("sms: SMS_ACCESS_KEY_ID / SMS_ACCESS_KEY_SECRET / SMS_SIGN_NAME / SMS_TEMPLATE_CODE not set")
		return api.PostAuthCode503JSONResponse{SmsUnavailableJSONResponse: smsUnavailable}, nil
	}
	if !a.ipCodes.fits(clientIP(ctx), 1) || !a.phoneCodes.take(phone, 1) || !a.ipCodes.take(clientIP(ctx), 1) {
		return api.PostAuthCode429JSONResponse{Error: api.ErrorCodeSmsTooFrequent}, nil
	}
	code, err := a.sms.call(ctx, "SendSmsVerifyCode", url.Values{
		"PhoneNumber":   {phone},
		"SignName":      {a.sms.signName},
		"TemplateCode":  {a.sms.template},
		"TemplateParam": {`{"code":"##code##","min":"5"}`},
		"CodeLength":    {"6"},
		"ValidTime":     {"300"},
		"Interval":      {"60"},
	}, nil)
	switch {
	case err != nil:
		log.Printf("sms send: %v", err)
	case code == "OK":
		return api.PostAuthCode204Response{}, nil
	case strings.Contains(code, "MOBILE_NUMBER_ILLEGAL"):
		return api.PostAuthCode400JSONResponse{Error: api.ErrorCodeInvalidPhone}, nil
	case strings.Contains(code, "FREQUENCY") || strings.Contains(code, "LIMIT_CONTROL"):
		return api.PostAuthCode429JSONResponse{Error: api.ErrorCodeSmsTooFrequent}, nil
	default:
		log.Printf("sms send: %s", code)
	}
	return api.PostAuthCode503JSONResponse{SmsUnavailableJSONResponse: smsUnavailable}, nil
}

func (s *server) PostAuthLogin(ctx context.Context, req api.PostAuthLoginRequestObject) (api.PostAuthLoginResponseObject, error) {
	phone := req.Body.Phone
	if !mainlandMobile.MatchString(phone) {
		return api.PostAuthLogin400JSONResponse{Error: api.ErrorCodeInvalidPhone}, nil
	}
	a := s.accounts
	// Every try counts, right or wrong, before asking Aliyun: a burst of guesses can't slip past the limit.
	// ponytail: someone who knows the number can use up its tries and lock it out for the hour; count
	// per number and IP if that gets abused.
	if !a.tries.take(phone, 1) {
		return api.PostAuthLogin429JSONResponse{RateLimitedJSONResponse: api.RateLimitedJSONResponse{Error: api.ErrorCodeRateLimited}}, nil
	}
	var res struct{ Model struct{ VerifyResult string } }
	if want, ok := a.testLogins[phone]; ok {
		if req.Body.Code == want {
			res.Model.VerifyResult = "PASS"
		}
	} else if code, err := a.sms.call(ctx, "CheckSmsVerifyCode", url.Values{"PhoneNumber": {phone}, "VerifyCode": {req.Body.Code}}, &res); err != nil || code != "OK" {
		log.Printf("sms check: %s %v", code, err)
		return api.PostAuthLogin503JSONResponse{SmsUnavailableJSONResponse: smsUnavailable}, nil
	}
	if res.Model.VerifyResult != "PASS" {
		return api.PostAuthLogin400JSONResponse{Error: api.ErrorCodeWrongCode}, nil
	}
	b := make([]byte, 32)
	rand.Read(b)
	token := base64.RawURLEncoding.EncodeToString(b)
	if err := a.users.login(ctx, phone, tokenHash(token)); err != nil {
		return nil, err
	}
	return api.PostAuthLogin200JSONResponse{Token: token}, nil
}

func (s *server) PostAuthLogout(ctx context.Context, _ api.PostAuthLogoutRequestObject) (api.PostAuthLogoutResponseObject, error) {
	if err := s.accounts.users.logout(ctx, sessionOf(ctx)); err != nil {
		return nil, err
	}
	return api.PostAuthLogout204Response{}, nil
}

func (s *server) GetMe(ctx context.Context, _ api.GetMeRequestObject) (api.GetMeResponseObject, error) {
	u := userOf(ctx)
	return api.GetMe200JSONResponse(u.me()), nil
}

func (u user) me() api.Me {
	return api.Me{Id: u.id, Phone: u.phone, Nickname: u.nickname, Avatar: u.avatar}
}

func tokenHash(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

type userKey struct{}
type sessionKey struct{}

// userOf is the caller on routes with security: bearerAuth.
func userOf(ctx context.Context) user {
	u, _ := ctx.Value(userKey{}).(user)
	return u
}

func sessionOf(ctx context.Context) []byte {
	h, _ := ctx.Value(sessionKey{}).([]byte)
	return h
}

// bearer guards the routes the spec marks bearerAuth (the generated code flags them with
// BearerAuthScopes) and puts the caller in the context; every other route passes untouched.
func bearer(users userStore) api.MiddlewareFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Context().Value(api.BearerAuthScopes) == nil {
				next.ServeHTTP(w, r)
				return
			}
			token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			var u user
			var err error
			hash := tokenHash(token)
			if ok && token != "" {
				u, ok, err = users.user(r.Context(), hash)
			}
			if err != nil {
				log.Printf("%s %s: %v", r.Method, r.URL.Path, err)
				writeJSON(w, http.StatusInternalServerError, api.Error{Error: api.ErrorCodeInternal})
				return
			}
			if !ok {
				writeJSON(w, http.StatusUnauthorized, api.Error{Error: api.ErrorCodeUnauthorized})
				return
			}
			ctx := context.WithValue(context.WithValue(r.Context(), userKey{}, u), sessionKey{}, hash)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// aliyunSMS calls 号码认证 (Dypnsapi 2017-05-25) over its RPC API, signed with signature v1.
type aliyunSMS struct {
	endpoint           string // https://dypnsapi.aliyuncs.com
	keyID, secret      string
	signName, template string
	client             *http.Client
}

func (a *aliyunSMS) configured() bool {
	return a != nil && a.keyID != "" && a.secret != "" && a.signName != "" && a.template != ""
}

// call runs action and returns Aliyun's Code ("OK" on success), decoding the answer into out if given.
func (a *aliyunSMS) call(ctx context.Context, action string, params url.Values, out any) (string, error) {
	nonce := make([]byte, 16)
	rand.Read(nonce)
	q := url.Values{
		"Action": {action}, "Version": {"2017-05-25"}, "Format": {"JSON"}, "AccessKeyId": {a.keyID},
		"SignatureMethod": {"HMAC-SHA1"}, "SignatureVersion": {"1.0"}, "SignatureNonce": {fmt.Sprintf("%x", nonce)},
		"Timestamp": {time.Now().UTC().Format("2006-01-02T15:04:05Z")},
	}
	for k, v := range params {
		q[k] = v
	}
	q.Set("Signature", aliyunSign("POST", q, a.secret))
	r, err := http.NewRequestWithContext(ctx, "POST", a.endpoint, strings.NewReader(q.Encode()))
	if err != nil {
		return "", err
	}
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := a.client.Do(r)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	var body json.RawMessage
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		return "", fmt.Errorf("%s: %d %w", action, res.StatusCode, err)
	}
	var c struct{ Code string }
	json.Unmarshal(body, &c)
	if out != nil {
		json.Unmarshal(body, out)
	}
	return c.Code, nil
}

// aliyunSign is the RPC signature v1: HMAC-SHA1 over the method and the sorted, percent-encoded parameters.
func aliyunSign(method string, q url.Values, secret string) string {
	// url.Values.Encode sorts by key; Aliyun wants spaces as %20, not +.
	enc := func(s string) string { return strings.ReplaceAll(url.QueryEscape(s), "+", "%20") }
	canon := strings.ReplaceAll(q.Encode(), "+", "%20")
	mac := hmac.New(sha1.New, []byte(secret+"&"))
	mac.Write([]byte(method + "&" + enc("/") + "&" + enc(canon)))
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}
