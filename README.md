# hm-dianping

一个基于 Spring Boot 和 Redis 的本地生活点评项目，包含短信验证码登录、商户缓存、优惠券秒杀、探店笔记、关注推送、附近商户、签到与 UV 统计等功能。仓库同时包含可直接部署到 nginx 的前端静态资源。

## 技术栈

- Java 8
- Spring Boot 2.3.12.RELEASE
- MyBatis-Plus 3.4.3
- MySQL
- Redis / Spring Data Redis / Lettuce
- Redisson 3.13.6
- Hutool 5.7.17
- Lua、Redis Stream、Bitmap、HyperLogLog、GEO、ZSet
- Vue 2 静态前端、Axios、Element UI、nginx

## 主要功能

- 手机号验证码登录、自动注册、Redis Token 与登录态续期
- 商户缓存、缓存空值、互斥锁重建、逻辑过期重建
- Redis 全局唯一 ID
- Lua 原子校验库存与一人一单
- Redis Stream 异步秒杀下单
- 乐观锁防止库存超卖，Redisson 分布式锁保证用户维度互斥
- 探店笔记发布、查询、点赞与点赞排行榜
- 关注、取关、共同关注和关注动态滚动分页
- Redis GEO 附近商户搜索与距离排序
- Redis Bitmap 用户签到与连续签到统计
- HyperLogLog 每日 UV 统计

## 项目结构

```text
hm-dianping/
├── frontend/                       # 前端静态资源和 nginx 配置
├── src/main/java/com/hmdp/
│   ├── config/                     # Web、MyBatis、Redisson 配置
│   ├── controller/                 # REST 接口
│   ├── dto/                        # 请求和响应对象
│   ├── entity/                     # 数据库实体
│   ├── mapper/                     # MyBatis-Plus Mapper
│   ├── service/                    # 业务接口与实现
│   └── utils/                      # 缓存、锁、ID、拦截器等工具
├── src/main/resources/
│   ├── db/hmdp.sql                 # 数据库初始化脚本
│   ├── mapper/                     # MyBatis XML
│   ├── seckill.lua                 # 秒杀原子校验脚本
│   └── application.yaml            # 公共配置
└── pom.xml
```

## 环境要求

- JDK 8 或兼容版本
- Maven 3.6+
- MySQL 5.7+
- Redis 6.x
- nginx（运行前端时需要）

## 启动后端

### 1. 初始化数据库

创建名为 `hmdp` 的数据库，然后执行：

```text
src/main/resources/db/hmdp.sql
```

默认连接地址为 `127.0.0.1:3306/hmdp`。

### 2. 启动 Redis

项目默认连接 `127.0.0.1:6380`。Docker 示例：

```bash
docker run -d --name hmdp-redis -p 6380:6379 redis:6.2 \
  redis-server --requirepass your-password
```

### 3. 创建本地配置

复制配置模板：

```powershell
Copy-Item src/main/resources/application-local.example.yaml `
  src/main/resources/application-local.yaml
```

修改 `application-local.yaml`，填写本机 MySQL 和 Redis 密码。该文件已加入 `.gitignore`，不会被提交。

也可以使用环境变量：

```text
MYSQL_USERNAME
MYSQL_PASSWORD
REDIS_PASSWORD
```

### 4. 运行项目

```bash
mvn spring-boot:run
```

后端地址：`http://127.0.0.1:8081`

## 启动前端

1. 将 `frontend/` 中的网页资源复制到 nginx 的 `html/hmdp/`。
2. 将 `frontend/nginx.conf` 复制到 nginx 的 `conf/nginx.conf`。
3. 启动 nginx。

Windows 示例：

```powershell
Copy-Item frontend/* D:/nginx/html/hmdp/ -Recurse -Force
Copy-Item frontend/nginx.conf D:/nginx/conf/nginx.conf -Force
Set-Location D:/nginx
./nginx.exe
```

访问：`http://127.0.0.1:8080`

nginx 会将 `/api/*` 请求转发到 `http://127.0.0.1:8081`。

## 常用接口

| 功能 | 方法 | 路径 |
| --- | --- | --- |
| 发送验证码 | POST | `/user/code` |
| 登录 | POST | `/user/login` |
| 当天签到 | POST | `/user/sign` |
| 连续签到统计 | GET | `/user/sign/count` |
| 商户类型 | GET | `/shop-type/list` |
| 商户详情 | GET | `/shop/{id}` |
| 附近商户 | GET | `/shop/of/type?typeId=1&current=1&x=120.15&y=30.32` |
| 秒杀下单 | POST | `/voucher-order/seckill/{voucherId}` |
| 热门笔记 | GET | `/blog/hot?current=1` |
| 每日 UV | GET | `/stats/uv?date=2026-10-24` |

除公开接口外，请在请求头中携带登录返回的 Token：

```http
authorization: your-token
```

## Redis Key 说明

| Key 前缀 | 用途 |
| --- | --- |
| `login:code:` | 登录验证码 |
| `login:token:` | 登录用户信息 |
| `cache:shop:` | 商户缓存 |
| `seckill:stock:` | 秒杀库存 |
| `seckill:order:` | 秒杀用户集合 |
| `stream.orders` | 秒杀订单消息流 |
| `blog:liked:` | 笔记点赞排行 |
| `feed:` | 关注动态收件箱 |
| `shop:geo:` | 商户 GEO 索引 |
| `sign:` | 用户月度签到 Bitmap |
| `uv:` | 每日 UV HyperLogLog |

## 注意事项

- HyperLogLog 为近似统计，存在约 0.81% 的标准误差。
- `application-local.yaml` 包含本机凭据，不要提交到 Git。
- 秒杀订单依赖 Redis Stream 消费组，应用启动后会自动创建并消费消息。
