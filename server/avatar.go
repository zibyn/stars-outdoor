package main

// 头像 (ux-v3 §8.4 第 6、8 条): a small JPEG on the account, stored like the 队伍对话's photos (chat.go) but
// outside the photo quota. A new one gets a new id, so the app caches by id; the old file goes.

import (
	"bytes"
	"context"
	"image"
	_ "image/jpeg"
	"io"
	"os"
	"path/filepath"

	"stars-outdoor/server/api"
)

const maxAvatarSide = 512 // the app sends 256×256

func (tm *teams) avatarPath(id string) string { return filepath.Join(tm.images, id+".avatar.jpg") }

func (s *server) PutMeAvatar(ctx context.Context, req api.PutMeAvatarRequestObject) (api.PutMeAvatarResponseObject, error) {
	b, err := io.ReadAll(req.Body) // capped at 1 MB by withMiddleware
	if err != nil {
		return api.PutMeAvatar400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil || format != "jpeg" || cfg.Width > maxAvatarSide || cfg.Height > maxAvatarSide {
		return api.PutMeAvatar400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	id := newImageID()
	if err := os.WriteFile(s.teams.avatarPath(id), b, 0o644); err != nil {
		return nil, err
	}
	u, err := s.setAvatar(ctx, &id)
	if err != nil {
		os.Remove(s.teams.avatarPath(id))
		return nil, err
	}
	return api.PutMeAvatar200JSONResponse(u.me()), nil
}

func (s *server) DeleteMeAvatar(ctx context.Context, _ api.DeleteMeAvatarRequestObject) (api.DeleteMeAvatarResponseObject, error) {
	u, err := s.setAvatar(ctx, nil)
	if err != nil {
		return nil, err
	}
	return api.DeleteMeAvatar200JSONResponse(u.me()), nil
}

// setAvatar stores the caller's new 头像, deletes the old one's file and tells their team.
func (s *server) setAvatar(ctx context.Context, avatar *string) (user, error) {
	u := userOf(ctx)
	old, err := s.accounts.users.setAvatar(ctx, u.id, avatar)
	if err != nil {
		return u, err
	}
	if old != nil {
		os.Remove(s.teams.avatarPath(*old))
	}
	u.avatar = avatar
	s.profileChanged(ctx, u.id)
	return u, nil
}

func (s *server) GetAvatar(ctx context.Context, req api.GetAvatarRequestObject) (api.GetAvatarResponseObject, error) {
	notFound := api.GetAvatar404JSONResponse{Error: api.ErrorCodeImageNotFound}
	if !imageID.MatchString(req.Avatar) { // it becomes a file name
		return notFound, nil
	}
	b, err := os.ReadFile(s.teams.avatarPath(req.Avatar))
	if os.IsNotExist(err) {
		return notFound, nil
	} else if err != nil {
		return nil, err
	}
	cache := "private, max-age=31536000, immutable"
	return api.GetAvatar200ImagejpegResponse{Body: bytes.NewReader(b), ContentLength: int64(len(b)), Headers: api.GetAvatar200ResponseHeaders{CacheControl: &cache}}, nil
}
