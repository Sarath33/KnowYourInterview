# NOTE: build context must be the REPO ROOT, not web/ — web/src imports
# ../../shared/types.ts (see web/src/App.tsx), so shared/ has to be visible to the
# build. On Railway, set this service's root directory to the repo root and its
# Dockerfile path to web/Dockerfile (both configurable independently).

FROM node:22-alpine AS build
WORKDIR /repo

COPY web/package.json web/package-lock.json web/
RUN npm --prefix web ci

COPY shared shared
COPY web web

# Baked into the static bundle at build time (Vite inlines import.meta.env.* at build,
# not at runtime) — Railway passes these as build ARGs. VITE_API_BASE_URL points at the
# api service's public domain (generated after the api service is deployed).
# VITE_GOOGLE_CLIENT_ID is the Google OAuth Web Client ID (blank = the "Continue with
# Google" button just renders nothing — see GoogleSignInButton.tsx).
ARG VITE_API_BASE_URL
ARG VITE_GOOGLE_CLIENT_ID
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_GOOGLE_CLIENT_ID=${VITE_GOOGLE_CLIENT_ID}
RUN npm --prefix web run build

FROM nginx:1.27-alpine
COPY --from=build /repo/web/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
