# Free Data APIs — No Signup Required

All endpoints work with `fetch_url` tool. No API keys needed (NASA DEMO_KEY is public and rate-limited but functional).

---

## Weather & Atmosphere

| API | URL |
|-----|-----|
| Open-Meteo Weather | `https://api.open-meteo.com/v1/forecast?latitude=43.6&longitude=-116.2&current_weather=true` |
| Open-Meteo Air Quality | `https://air-quality-api.open-meteo.com/v1/air-quality?latitude=43.6&longitude=-116.2&current=pm10,pm2_5,carbon_monoxide,ozone` |
| Open-Meteo Solar Radiation | `https://api.open-meteo.com/v1/forecast?latitude=43.6&longitude=-116.2&hourly=shortwave_radiation,direct_radiation` |
| NOAA/NWS Forecast | `https://api.weather.gov/points/{lat},{lon}` |
| NOAA/NWS Alerts | `https://api.weather.gov/alerts/active?area={state}` |
| NOAA GOES Satellite Image | `https://cdn.star.nesdis.noaa.gov/GOES16/ABI/CONUS/GEOCOLOR/latest.jpg` |
| OpenAQ Air Quality | `https://api.openaq.org/v2/latest?coordinates={lat},{lon}&radius=25000` |
| UV Index | `https://api.uvindex.io/api/v1?lat={lat}&lon={lon}` |

## Space & Astronomy

| API | URL |
|-----|-----|
| NASA APOD | `https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY` |
| NASA EONET (natural events) | `https://eonet.gsfc.nasa.gov/api/v3/events` |
| NASA DONKI (solar flares/CMEs) | `https://api.nasa.gov/DONKI/FLR?api_key=DEMO_KEY` |
| NASA NeoWs (asteroids) | `https://api.nasa.gov/neo/rest/v1/feed?api_key=DEMO_KEY` |
| NASA Mars Rover Photos | `https://api.nasa.gov/mars-photos/api/v1/rovers/curiosity/latest_photos?api_key=DEMO_KEY` |
| NASA Earth Imagery | `https://api.nasa.gov/planetary/earth/imagery?lon={lon}&lat={lat}&api_key=DEMO_KEY` |
| ISS Position | `http://api.open-notify.org/iss-now.json` |
| People in Space | `http://api.open-notify.org/astros.json` |
| Sunrise/Sunset | `https://api.sunrise-sunset.org/json?lat={lat}&lng={lon}` |
| US Naval Observatory | `https://aa.usno.navy.mil/api/rstt/oneday?date={YYYY-MM-DD}&coords={lat},{lon}` |

## Earthquakes & Geology

| API | URL |
|-----|-----|
| USGS Earthquakes (hour) | `https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson` |
| USGS Earthquakes (day) | `https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson` |
| USGS Significant (month) | `https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_month.geojson` |
| USGS Water/Rivers | `https://waterservices.usgs.gov/nwis/iv/?format=json&sites={siteId}&parameterCd=00060` |

## Ocean & Marine

| API | URL |
|-----|-----|
| NOAA Tides | `https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?date=latest&station={stationId}&product=water_level&datum=MLLW&units=english&time_zone=lst_ldt&format=json` |
| NOAA Buoys | `https://www.ndbc.noaa.gov/data/realtime2/{buoyId}.txt` |
| NOAA Currents | `https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?date=latest&station={stationId}&product=currents&units=english&time_zone=lst_ldt&format=json` |
| NOAA Water Temp | `https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?date=latest&station={stationId}&product=water_temperature&units=english&format=json` |

## Aviation & Flight

| API | URL |
|-----|-----|
| OpenSky Network (global) | `https://opensky-network.org/api/states/all` |
| OpenSky Network (area) | `https://opensky-network.org/api/states/all?lamin={lat1}&lomin={lon1}&lamax={lat2}&lomax={lon2}` |

## Fire & Hazards

| API | URL |
|-----|-----|
| NASA FIRMS (active fires) | `https://firms.modaps.eosdis.nasa.gov/api/area/csv/DEMO_KEY/VIIRS_SNPP_NRT/world/1` |
| NASA Sentry (impact risk) | `https://ssd-api.jpl.nasa.gov/sentry.api` |
| NASA SBDB (small bodies) | `https://ssd-api.jpl.nasa.gov/sbdb.api?sstr={name}` |

## Climate & Historical

| API | URL |
|-----|-----|
| NOAA Climate | `https://www.ncei.noaa.gov/access/services/data/v1?dataset=daily-summaries&stations={stationId}&startDate={start}&endDate={end}&format=json` |
| Open-Meteo Historical | `https://archive-api.open-meteo.com/v1/archive?latitude={lat}&longitude={lon}&start_date={start}&end_date={end}&daily=temperature_2m_max` |

## Geolocation & Time

| API | URL |
|-----|-----|
| IP Geolocation | `http://ip-api.com/json/` |
| WorldTimeAPI | `http://worldtimeapi.org/api/ip` |

---

## Notes
- NASA `DEMO_KEY` is rate-limited (30 req/hr, 50 req/day) but works without signup
- Open-Meteo is free for non-commercial use, unlimited requests
- NOAA/NWS is US government public data, no limits
- USGS is US government public data, no limits
- OpenSky has anonymous rate limits (~100 req/day)
- Replace `{lat}`, `{lon}`, `{stationId}`, etc. with actual values
