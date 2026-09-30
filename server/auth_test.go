package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
)

// fakeAliyun answers like 号码认证: it checks the request is signed, remembers which numbers were
// sent a code, and says PASS for 123456 on those.
type fakeAliyun struct {
	mu    sync.Mutex
	sent  map[string]int
	fail  string // a Code to answer SendSmsVerifyCode with instead of OK
	calls []url.Values
}

func (f *fakeAliyun) serve(t *testing.T) *httptest.Server {
	f.sent = map[string]int{}
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		f.mu.Lock()
		defer f.mu.Unlock()
		q := r.PostForm
		f.calls = append(f.calls, q)
		if q.Get("Signature") == "" || q.Get("AccessKeyId") != "ID" || q.Get("Version") != "2017-05-25" {
			w.WriteHeader(400)
			w.Write([]byte(`{"Code":"InvalidAccessKeyId.NotFound"}`))
			return
		}
		switch q.Get("Action") {
		case "SendSmsVerifyCode":
			if f.fail != "" {
				w.WriteHeader(400)
				w.Write([]byte(`{"Code":"` + f.fail + `","Message":"x"}`))
				return
			}
			f.sent[q.Get("PhoneNumber")]++
			w.Write([]byte(`{"Code":"OK","Success":true,"Model":{"BizId":"1"}}`))
		case "CheckSmsVerifyCode":
			res := "UNKNOWN"
			if f.sent[q.Get("PhoneNumber")] > 0 && q.Get("VerifyCode") == "123456" {
				res = "PASS"
			}
			w.Write([]byte(`{"Code":"OK","Success":true,"Model":{"VerifyResult":"` + res + `"}}`))
		}
	}))
	t.Cleanup(up.Close)
	return up
}

// memUsers is userStore in memory.
type memUsers struct {
	mu       sync.Mutex
	phones   []string
	sessions map[string]int64 // token hash → user id
}

func (m *memUsers) login(_ context.Context, phone string, hash []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	id := int64(0)
	for i, p := range m.phones {
		if p == phone {
			id = int64(i + 1)
		}
	}
	if id == 0 {
		m.phones = append(m.phones, phone)
		id = int64(len(m.phones))
	}
	m.sessions[string(hash)] = id
	return nil
}

func (m *memUsers) user(_ context.Context, hash []byte) (user, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.sessions[string(hash)]
	if !ok {
		return user{}, false, nil
	}
	return user{id, m.phones[id-1]}, true, nil
}

func (m *memUsers) logout(_ context.Context, hash []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.sessions, string(hash))
	return nil
}

func authHandler(t *testing.T, f *fakeAliyun, keyID string) http.Handler {
	sms := &aliyunSMS{endpoint: f.serve(t).URL, keyID: keyID, secret: "SECRET", signName: "速通互联验证码", template: "100001", client: http.DefaultClient}
	return withMiddleware(routes(1, okDB, nil, nil, nil, nil, newAccounts(sms, &memUsers{sessions: map[string]int64{}}), nil, nil), 1)
}

func postJSON(h http.Handler, path, body string, hdr ...string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", path, bytes.NewBufferString(body))
	r.Header.Set("Content-Type", "application/json")
	for i := 0; i < len(hdr); i += 2 {
		r.Header.Set(hdr[i], hdr[i+1])
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func login(t *testing.T, h http.Handler, phone string) string {
	t.Helper()
	if w := postJSON(h, "/v1/auth/code", `{"phone":"`+phone+`"}`); w.Code != 204 {
		t.Fatalf("code: %d %s", w.Code, w.Body)
	}
	w := postJSON(h, "/v1/auth/login", `{"phone":"`+phone+`","code":"123456"}`)
	var res struct{ Token string }
	if json.Unmarshal(w.Body.Bytes(), &res); w.Code != 200 || len(res.Token) < 40 {
		t.Fatalf("login: %d %s", w.Code, w.Body)
	}
	return res.Token
}

func TestLoginWithTextedCodeThenMeThenLogout(t *testing.T) {
	f := &fakeAliyun{}
	h := authHandler(t, f, "ID")
	tok := login(t, h, "13800138000")
	send := f.calls[0]
	if send.Get("SignName") != "速通互联验证码" || send.Get("TemplateCode") != "100001" || send.Get("CodeLength") != "6" || !strings.Contains(send.Get("TemplateParam"), "##code##") {
		t.Fatalf("send params: %v", send)
	}
	bearer := []string{"Authorization", "Bearer " + tok}
	if w := get(h, "/v1/me", bearer...); w.Code != 200 || !strings.Contains(w.Body.String(), `"phone":"13800138000"`) || !strings.Contains(w.Body.String(), `"id":1`) {
		t.Fatalf("me: %d %s", w.Code, w.Body)
	}
	// Logging in again (another phone of the same person) is the same account, with a second token.
	tok2 := login(t, h, "13800138000")
	if w := get(h, "/v1/me", "Authorization", "Bearer "+tok2); !strings.Contains(w.Body.String(), `"id":1`) || tok2 == tok {
		t.Fatalf("second login: %s", w.Body)
	}
	if w := postJSON(h, "/v1/auth/logout", "", bearer...); w.Code != 204 {
		t.Fatalf("logout: %d %s", w.Code, w.Body)
	}
	if w := get(h, "/v1/me", bearer...); w.Code != 401 {
		t.Fatalf("after logout: %d", w.Code)
	}
	if w := get(h, "/v1/me", "Authorization", "Bearer "+tok2); w.Code != 200 {
		t.Fatalf("other session after logout: %d", w.Code)
	}
}

func TestBearerRoutesRefuseMissingOrUnknownTokens(t *testing.T) {
	h := authHandler(t, &fakeAliyun{}, "ID")
	for _, hdr := range [][]string{nil, {"Authorization", "Bearer nope"}, {"Authorization", "nope"}} {
		if w := get(h, "/v1/me", hdr...); w.Code != 401 || !strings.Contains(w.Body.String(), `"unauthorized"`) {
			t.Errorf("%v: %d %s", hdr, w.Code, w.Body)
		}
		if w := postJSON(h, "/v1/auth/logout", "", hdr...); w.Code != 401 {
			t.Errorf("logout %v: %d", hdr, w.Code)
		}
	}
	// Anonymous routes stay anonymous.
	if w := get(h, "/v1/version"); w.Code != 200 {
		t.Fatalf("version: %d", w.Code)
	}
}

func TestOnlyMainlandMobileNumbers(t *testing.T) {
	f := &fakeAliyun{}
	h := authHandler(t, f, "ID")
	for _, p := range []string{"", "1380013800", "138001380001", "+8613800138000", "23800138000", "12800138000", "1380013800a"} {
		if w := postJSON(h, "/v1/auth/code", `{"phone":"`+p+`"}`); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_phone") {
			t.Errorf("code %q: %d %s", p, w.Code, w.Body)
		}
		if w := postJSON(h, "/v1/auth/login", `{"phone":"`+p+`","code":"123456"}`); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_phone") {
			t.Errorf("login %q: %d %s", p, w.Code, w.Body)
		}
	}
	if len(f.calls) != 0 {
		t.Fatalf("aliyun called for bad numbers: %v", f.calls)
	}
}

func TestWrongCodeAndBruteForce(t *testing.T) {
	h := authHandler(t, &fakeAliyun{}, "ID")
	postJSON(h, "/v1/auth/code", `{"phone":"13900139000"}`)
	for i := 0; i < loginTries; i++ {
		if w := postJSON(h, "/v1/auth/login", `{"phone":"13900139000","code":"000000"}`); w.Code != 400 || !strings.Contains(w.Body.String(), "wrong_code") {
			t.Fatalf("try %d: %d %s", i, w.Code, w.Body)
		}
	}
	// Out of tries: even the right code waits out the hour.
	if w := postJSON(h, "/v1/auth/login", `{"phone":"13900139000","code":"123456"}`); w.Code != 429 {
		t.Fatalf("after %d tries: %d %s", loginTries, w.Code, w.Body)
	}
}

func TestSMSFailures(t *testing.T) {
	f := &fakeAliyun{fail: "FREQUENCY_FAIL"}
	h := authHandler(t, f, "ID")
	if w := postJSON(h, "/v1/auth/code", `{"phone":"13800138000"}`); w.Code != 429 || !strings.Contains(w.Body.String(), "sms_too_frequent") {
		t.Fatalf("frequency: %d %s", w.Code, w.Body)
	}
	f.fail = "MOBILE_NUMBER_ILLEGAL"
	if w := postJSON(h, "/v1/auth/code", `{"phone":"13800138000"}`); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_phone") {
		t.Fatalf("illegal: %d %s", w.Code, w.Body)
	}
	f.fail = "FUNCTION_NOT_OPENED"
	if w := postJSON(h, "/v1/auth/code", `{"phone":"13800138000"}`); w.Code != 503 || !strings.Contains(w.Body.String(), "sms_unavailable") {
		t.Fatalf("not opened: %d %s", w.Code, w.Body)
	}
	// Not configured: nothing reaches Aliyun.
	f2 := &fakeAliyun{}
	if w := postJSON(authHandler(t, f2, ""), "/v1/auth/code", `{"phone":"13800138000"}`); w.Code != 503 || len(f2.calls) != 0 {
		t.Fatalf("unconfigured: %d %v", w.Code, f2.calls)
	}
}

func TestCodesPerIPAreCapped(t *testing.T) {
	h := authHandler(t, &fakeAliyun{}, "ID")
	n := 0
	for i := 0; i < ipCodesPerDay+5; i++ {
		if postJSON(h, "/v1/auth/code", `{"phone":"138001380`+string(rune('0'+i/10))+string(rune('0'+i%10))+`"}`).Code == 204 {
			n++
		}
	}
	if n != ipCodesPerDay {
		t.Fatalf("%d codes from one IP", n)
	}
}

// Aliyun's documented example for signature v1 (RPC): the same canonical string gives the same signature.
func TestAliyunSignature(t *testing.T) {
	q := url.Values{"Format": {"XML"}, "AccessKeyId": {"testid"}, "Action": {"DescribeRegions"}, "SignatureMethod": {"HMAC-SHA1"},
		"SignatureNonce": {"3ee8c1b8-83d3-44af-a94f-4e0ad82fd6cf"}, "SignatureVersion": {"1.0"}, "Timestamp": {"2016-02-23T12:46:24Z"}, "Version": {"2014-05-26"}}
	if got := aliyunSign("GET", q, "testsecret"); got != "OLeaidS1JvxuMvnyHOwuJ+uX5qY=" {
		t.Fatalf("got %s", got)
	}
}
