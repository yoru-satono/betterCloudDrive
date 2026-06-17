FROM node:24-alpine AS builder
WORKDIR /app
ARG VITE_API_BASE_URL=
ARG VITE_WEB_BASE_URL=
ARG VITE_DESKTOP_API_BASE_URL=http://127.0.0.1:8080/api/v1
ARG VITE_DESKTOP_WEB_BASE_URL=http://127.0.0.1:3000
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_WEB_BASE_URL=$VITE_WEB_BASE_URL
ENV VITE_DESKTOP_API_BASE_URL=$VITE_DESKTOP_API_BASE_URL
ENV VITE_DESKTOP_WEB_BASE_URL=$VITE_DESKTOP_WEB_BASE_URL
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/templates/default.conf.template
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
