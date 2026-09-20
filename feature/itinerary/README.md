# feature:itinerary

The Trips tab: create trips (name, destination, date range, cover emoji/color) holding
ordered, day-grouped itinerary items — places (GPS coordinates, planned time, notes,
category, link) and commute legs (mode, from → to, optional link to a train/flight
journey via `core:data` contracts, never a direct feature dependency). The trip screen
toggles between a day-wise timeline view and a Google Maps Compose map view (numbered
markers by day, day polyline, my-location layer; API key from `local.properties`).
Everything works fully offline once created — marker/list data is local. The skeleton
ships the tab route and empty state.
