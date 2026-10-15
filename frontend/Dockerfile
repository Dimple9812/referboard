# Build the static app, then serve it with nginx, which also proxies /api to the API gateway (same origin, so the
# httpOnly refresh cookie and CORS need no special setup).
FROM node:22.20.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_GOOGLE_CLIENT_ID=""
ARG VITE_EXTENSION_IDS=""
ENV VITE_GOOGLE_CLIENT_ID=${VITE_GOOGLE_CLIENT_ID} VITE_EXTENSION_IDS=${VITE_EXTENSION_IDS}
RUN npm run build

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV GATEWAY_URL=http://api-gateway:8080
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/healthz || exit 1
