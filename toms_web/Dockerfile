FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache openssl
COPY nginx.conf.template /etc/nginx/toms.conf.template
COPY docker-entrypoint-config.sh /docker-entrypoint.d/40-toms-config.sh
RUN sed -i 's/\r$//' /docker-entrypoint.d/40-toms-config.sh && chmod +x /docker-entrypoint.d/40-toms-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 443
