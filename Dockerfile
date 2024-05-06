# Register repository in a region:
# GCP_PROJECT_ID=tsvet-prj
# GCP_REPOSITORY_NAME=basic-http-srv
# GCP_REGION=us-east4
# GCP_IMAGE_NAME=basic-http-srv-gke
# gcloud artifacts repositories create "${GCP_REPOSITORY_NAME}" \
#    --project="${GCP_PROJECT_ID}" \
#    --repository-format=docker \
#    --location="${GCP_REGION}" \
#    --description="My docker repository for awesome images."
#
# Create an image file and submit it in the registry:
# gcloud builds submit --tag "${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${GCP_REPOSITORY_NAME}/${GCP_IMAGE_NAME}" .

# FROM alpine:latest
FROM google/cloud-sdk:alpine AS sdk
USER root

RUN mkdir /http-srv
WORKDIR /http-srv

# Copy the source into the project dir
RUN gsutil cp gs://tsvet-scripts/k8s/basic-http-srv.tar.gz .

# Build process
FROM maven:3.8.6-openjdk-8-slim AS build
COPY --from=sdk /http-srv/basic-http-srv.tar.gz /http-srv/basic-http-srv.tar.gz
WORKDIR /http-srv
RUN ls -l basic-http-srv.tar.gz
RUN tar -zxf basic-http-srv.tar.gz
RUN rm basic-http-srv.tar.gz
RUN find /http-srv -type f 
RUN mvn -f /http-srv/pom.xml -DskipTests clean package

# JDK runtime
FROM openjdk:8-jre-slim
COPY --from=build /http-srv/target/basic-http-srv-0.1.0.jar /usr/local/lib/basic-http-srv.jar
COPY --from=build /http-srv/src/main/java/logging.properties /usr/local/lib/logging.properties
RUN ls -l /usr/local/lib/basic-http-srv.jar /usr/local/lib/logging.properties

# Run the web service on container startup.
# For some reason, the env variables are not getting replaced even though defined
# in the deployment.yaml file.
CMD ["java","-Dgcplogging",\
  "-Djava.util.logging.config.file=/usr/local/lib/logging.properties",\
  "-classpath","/usr/local/lib/basic-http-srv.jar",\
  "com.goo.test.k8s.HttpServer",\
  "-p","${HTTPSRV_PORT}","-b","${HTTPSRV_BACKLOG}","-t","${HTTPSRV_RUNTIME}"]
