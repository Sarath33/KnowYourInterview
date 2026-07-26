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
# not at runtime) — Railway passes this as a build ARG pointing at the api service's
# public domain, generated after the api service is deployed.
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm --prefix web run build

FROM nginx:1.27-alpine
COPY --from=build /repo/web/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
