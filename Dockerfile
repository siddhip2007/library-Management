FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY Main.java .
COPY index.html .

RUN javac --add-modules jdk.httpserver Main.java

EXPOSE 10000

CMD ["java", "--add-modules", "jdk.httpserver", "Main"]
