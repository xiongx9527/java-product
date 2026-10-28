-- 集群就绪：图表图片 token 映射
-- 原先 token → 文件 url 的映射存在进程本地 JSON 中，多实例下命中其它实例会 404。
CREATE TABLE chart_image_token (
    token       VARCHAR(128) PRIMARY KEY,
    file_url    VARCHAR(2000) NOT NULL,
    expire_at   BIGINT        NOT NULL,
    create_time BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_chart_image_token_expire ON chart_image_token (expire_at);
