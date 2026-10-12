FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint-cert.sh /docker-entrypoint.d/40-selfsigned-cert.sh
RUN sed -i 's/\r$//' /docker-entrypoint.d/40-selfsigned-cert.sh && chmod +x /docker-entrypoint.d/40-selfsigned-cert.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 443
