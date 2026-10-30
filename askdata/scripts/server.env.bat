@echo off
REM server.bat 会自动调用本文件（如不需要可删除）。
REM 在此集中配置 askdata-server jar 运行所需环境变量；也可在调用前自行 set 覆盖。

set "ASKDATA_SECRET_KEY=change-me"
set "SPRING_PROFILES_ACTIVE=mysql"
set "ASKDATA_DB_URL=jdbc:mysql://127.0.0.1:3306/askdata?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
set "ASKDATA_DB_USERNAME=askdata"
set "ASKDATA_DB_PASSWORD=change-me"

set "ASKDATA_REDIS_HOST=127.0.0.1"
set "ASKDATA_REDIS_PORT=6379"
set "ASKDATA_REDIS_PASSWORD="

set "ASKDATA_CACHE_TYPE=memory"
set "ASKDATA_SA_TOKEN_STORE="

set "FILE_STORAGE_PLATFORM=local"

set "ASKDATA_EMBEDDING_MODEL_PATH=/opt/askdata/models"
set "ASKDATA_VECTOR_STORE_TYPE=qdrant"
set "ASKDATA_QDRANT_URL=http://127.0.0.1:6333"
set "ASKDATA_QDRANT_GRPC_PORT=6334"
set "ASKDATA_VECTOR_RECALL_THRESHOLD=0.4"
