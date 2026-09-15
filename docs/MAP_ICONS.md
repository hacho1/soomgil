# Map Icons

## Presentation

- All nine discovery categories have matching local artwork on map pins, the legend, and the place list.
- The main icon always represents the stored place category. Favorites add a red heart; following recommendations add a blue people indicator. Both indicators can coexist.
- Unknown categories use a map icon. No category or facility is inferred from a name or image.
- Category navigation and filtering share the discovery taxonomy, including the history/culture and leisure/experience aliases.
- Nearby markers are grouped with Leaflet.markercluster 1.5.3 at low zoom. Selecting a group zooms in; coincident markers can spread apart at maximum zoom.
- Only the selected pin is enlarged. The transform is applied inside the Leaflet-positioned marker, and reduced-motion preferences disable the scale transition.
- The user's location remains a blue dot. Repeated location requests reuse that marker, and late callbacks are ignored after leaving the page.
- Following places are loaded only for a signed-in user. Following-list loading and failure states are separate from an empty result.

## Assets

Microsoft Fluent Emoji, MIT license:

- Source: https://github.com/microsoft/fluentui-emoji
- Pinned revision: `1ffb34c752ecf5d402f04cfb4b392c77f57c54bc`
- Manifest: `frontend/src/data/map-art.json`
- Ten original 256px PNG files, 402,138 bytes total, in `frontend/public/assets/map/`.
- Original license and SHA-256 inventory are retained in the same directory. Images are served locally, not requested from GitHub at runtime.
- Refresh reproducibly: `node scripts/fetch-badge-assets.mjs map`.

Marker clustering: https://github.com/Leaflet/Leaflet.markercluster (MIT).

## Verification

Run `npm.cmd test` and `npm.cmd run build` from `frontend`.
Map tests cover category coverage and aliases, combined social indicators, invalid coordinates, and asset dimensions/hashes/license.
Browser checks should cover small mobile layouts, both color modes, category navigation, cluster expansion, marker previews, and the detailed-place dialog.
