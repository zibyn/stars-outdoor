package main

// 平台轨迹 (spec §2.8, ADR 0005): the 轨迹 the platform vouches for, from official open data
// (scripts/build-platform.py, imported once) or a 公开轨迹 promoted to a copy of its own. Never merged
// with OSM (ADR 0002).
// ponytail: 晋升 and 下架 are SQL by hand (deploy/README.md); an admin endpoint or page once others do them.

import (
	"context"

	"stars-outdoor/server/api"
)

// A row is a 平台轨迹; source is its credit (the open data's licence, or the author). promoted_* name the
// 公开轨迹 it was copied from, without a foreign key: the copy outlives the original.
// shown_public_tracks is public_tracks less the promoted, which the copies stand in for.
// promote_track(user, id, credit) copies a 公开轨迹 (as shown: its ends hidden) under its track's name, and
// fails if there is no such 公开轨迹 or it was promoted already.
const platformTracksSchema = `
CREATE TABLE IF NOT EXISTS platform_tracks (
	id bigserial PRIMARY KEY,
	name text NOT NULL,
	source text NOT NULL,
	geom geometry(MultiLineString, 4326) NOT NULL,
	promoted_user bigint,
	promoted_id text,
	UNIQUE (promoted_user, promoted_id)
);
CREATE INDEX IF NOT EXISTS platform_tracks_geom ON platform_tracks USING gist (geom);
CREATE OR REPLACE VIEW shown_public_tracks AS SELECT * FROM public_tracks p
	WHERE NOT EXISTS (SELECT 1 FROM platform_tracks c WHERE c.promoted_user = p.user_id AND c.promoted_id = p.id);
CREATE OR REPLACE FUNCTION promote_track(bigint, text, text) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE copy bigint;
BEGIN
	INSERT INTO platform_tracks (name, source, geom, promoted_user, promoted_id)
	SELECT t.name, $3, p.geom, p.user_id, p.id FROM public_tracks p JOIN sync_tracks t USING (user_id, id)
	WHERE p.user_id = $1 AND p.id = $2 RETURNING id INTO copy;
	IF copy IS NULL THEN
		RAISE EXCEPTION 'no 公开轨迹 % of user %', $2, $1;
	END IF;
	RETURN copy;
END $$;`

// GetPlatformTracksTile needs no login. From z 8: there are few, and they are what to follow.
func (s *server) GetPlatformTracksTile(ctx context.Context, req api.GetPlatformTracksTileRequestObject) (api.GetPlatformTracksTileResponseObject, error) {
	tile, err := s.vectorTile(ctx, "platform_tracks", "platform_tracks", "name, source", 8, req.Z, req.X, req.Y)
	if err != nil {
		return nil, err
	} else if tile == nil {
		return api.GetPlatformTracksTile400JSONResponse{Error: api.ErrorCodeInvalidRequest}, nil
	}
	return api.GetPlatformTracksTile200ApplicationvndMapboxVectorTileResponse{Body: tile, ContentLength: int64(tile.Len()),
		Headers: api.GetPlatformTracksTile200ResponseHeaders{CacheControl: &tileCache}}, nil
}
