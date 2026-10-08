# ------------------------------
# 第1段階：Spring Bootをビルドする環境
# ------------------------------

# Java 21のJDKを使用する。
# JDKはJavaプログラムのコンパイルに必要。
FROM eclipse-temurin:21-jdk AS build

# Dockerコンテナ内の作業場所を/appにする。
WORKDIR /app

# プロジェクト一式をコンテナへコピーする。
COPY . .

# Windows上のgradlewにCRLF改行が含まれていても
# Linux上で実行できるように改行コードを整える。
RUN sed -i 's/\r$//' gradlew

# Linux上でGradle Wrapperを実行できるようにする。
RUN chmod +x gradlew

# Spring Bootの実行可能JARを作成する。
# bootJarはSpring Bootアプリ用のJARを生成するGradleタスク。
RUN ./gradlew clean bootJar --no-daemon


# ------------------------------
# 第2段階：実際にアプリを動かす環境
# ------------------------------

# 実行だけなので、コンパイラを含まないJREを使う。
# JDKより小さな実行環境にできる。
FROM eclipse-temurin:21-jre

WORKDIR /app

# 上のbuild段階で生成したJARだけをコピーする。
COPY --from=build /app/build/libs/*.jar app.jar

# Renderで一般的に利用されるWeb Service用ポート。
# 実際の待受ポートはapplication.propertiesのPORT設定に従う。
EXPOSE 10000

# Spring Bootアプリを起動する。
ENTRYPOINT ["java", "-jar", "app.jar"]
