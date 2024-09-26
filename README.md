# docker-web-handler(Java, Quarkus, Maven)

Simple application to visualize and manipulate docker images and containers from web browser.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

## Use with docker

1. Pull image:
```shell script
docker pull fabriciozrk/docker-web-handler:latest
```
2. Run with:
```shell script
docker run -d -p 8080:8080 --restart=always -v /var/run/docker.sock:/var/run/docker.sock fabriciozrk/docker-web-handler
```
---

## Run the application with live coding

You can run your application in dev mode that enables live coding using:

1. Start the application with:
```shell script
./mvnw compile quarkus:dev
```

2. Visit <b><i> http://localhost:8080 </i></b>

3. Make some changes in the source code.

4. Refresh your browser (F5).  

<i> Notice that those changes are immediately in effect. </i>

---

## Pack and run the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `docker-web-handler-1.0.0-SNAPSHOT-runner.jar` file in the `/target` directory. <br>

Run it:
```shell script 
java -jar target/docker-web-handler-1.0.0-SNAPSHOT-runner.jar
```
<br>

---

## Creating a native executable

1. [Install GraalVM and install the native-image tool](https://quarkus.io/guides/building-native-image#configuring-graalvm)

2. Compile it natively:
```shell script
./mvnw package -Pnative
```

3. Or, if you don't have GraalVM installed, you can run the native executable build in a dockerContainer using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.dockerContainer-build=true
```
4. Run the native executable:

```shell script
./target/docker-web-handler-1.0.0-SNAPSHOT-runner
```
