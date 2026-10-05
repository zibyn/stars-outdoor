package main

// 昵称 (ux-v3 §8.4 第 5、8 条): the account's name in every team. A new account gets a default from 附录 A; a
// change goes out to the caller's current team over its socket, so teammates show it at once.

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgxpool"

	"stars-outdoor/server/api"
)

// defaultNames is ux-v3 附录 A: a default nickname is one of these and two digits, e.g. 岩羊27.
var defaultNames = strings.Fields(`岩羊 红隼 雪豹 松鸦 金雕 白桦 黄鹂 杜鹃 云杉 藏羚 马鹿 牦牛 鼠兔 苍鹰 冷杉 胡杨 血雉 斑羚 猞猁 狍子
	赤狐 棕熊 灰鹤 戴胜 翠鸟 画眉 百灵 雨燕 山雀 银杏 油松 落叶松 青冈 龙胆 格桑花 绿绒蒿 点地梅 报春 紫菀 花楸
	沙棘 柳莺 石鸡 蓝马鸡 朱鹮 黑颈鹤 秃鹫 林麝 羚牛 水獭`)

// defaultNickname is random: not checked against others, nothing to do with the number.
func defaultNickname() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(defaultNames))*100))
	i := int(n.Int64())
	return fmt.Sprintf("%s%02d", defaultNames[i/100], i%100)
}

const maxNickname = 12

// nickname is name trimmed; ok is false if that's empty or over 12 characters.
func nickname(name string) (string, bool) {
	name = strings.TrimSpace(name)
	return name, name != "" && utf8.RuneCountInString(name) <= maxNickname
}

// fillNicknames gives a default to every account without a nickname: those the migration in teamsSchema
// found no name of their own for. Run at every start, after the schema.
// ponytail: one UPDATE per account, once; fine for the accounts there are before launch.
func fillNicknames(ctx context.Context, db *pgxpool.Pool) error {
	rows, _ := db.Query(ctx, "SELECT id FROM users WHERE nickname IS NULL")
	var ids []int64
	var id int64
	for rows.Next() {
		if err := rows.Scan(&id); err != nil {
			return err
		}
		ids = append(ids, id)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	for _, id := range ids {
		if _, err := db.Exec(ctx, "UPDATE users SET nickname = $2 WHERE id = $1 AND nickname IS NULL", id, defaultNickname()); err != nil {
			return err
		}
	}
	return nil
}

func (s *server) PutMeNickname(ctx context.Context, req api.PutMeNicknameRequestObject) (api.PutMeNicknameResponseObject, error) {
	name, ok := nickname(req.Body.Nickname)
	if !ok {
		return api.PutMeNickname400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	u := userOf(ctx)
	if err := s.accounts.users.setNickname(ctx, u.id, name); err != nil {
		return nil, err
	}
	// 成员资料变了: a change with nothing to store sends each socket the team, with the new name.
	var ids []int64
	if s.teams != nil {
		var err error
		if ids, err = s.teams.store.activeTeams(ctx, u.id); err != nil {
			return nil, err
		}
	}
	for _, id := range ids {
		if err := s.teams.change(ctx, id, u.id, func(api.Team, api.Member) error { return nil }); err != nil && !errors.Is(err, errNotMember) {
			return nil, err
		}
	}
	return api.PutMeNickname200JSONResponse{Id: u.id, Phone: u.phone, Nickname: name}, nil
}
