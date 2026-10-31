# TORO-Proxy の JAR だけを入れたイメージです (動かすものではありません)。
# サーバーの update_velocity.sh が、ここから JAR を取り出して Velocity の plugins/ に入れます。
#   docker create ghcr.io/toro-server/toro-proxy:<版> /TORO-Proxy.jar   # scratch には起動コマンドが無いので、形だけ渡す
#   docker cp <作ったコンテナ>:/TORO-Proxy.jar ./TORO-Proxy.jar
# JAR は、先に `gradle shadowJar` で作って dist/TORO-Proxy.jar に置きます (CI がやります)
FROM scratch
COPY dist/TORO-Proxy.jar /TORO-Proxy.jar
