# ---- 构建阶段：Vite 构建 ----
FROM node:22-alpine AS build
WORKDIR /app
# 国内网络用 npmmirror 加速
RUN npm config set registry https://registry.npmmirror.com
COPY package.json package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY . .
RUN npm run build

# ---- 运行阶段：nginx 托管前端静态资源并反代 /api ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
