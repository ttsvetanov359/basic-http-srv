### Procedure

Upload the `Dockerfile` and yaml definitions into the bucket.

```
GCS_URL=gs://my-bucket-name/my-folder-name
gsutil cp Dockerfile deployment.yaml service.yaml ${GCS_URL}
```

Login to the cloud console.
Get the `Dockerfile` from the GCS.

```
gsutil cp ${GCS_URL}/Dockerfile .
```

Create the repo and build the image.

```
GCP_PROJECT_ID=tsvet-prj
GCP_REPOSITORY_NAME=basic-http-srv
GCP_REGION=us-east4
GCP_IMAGE_NAME=basic-http-srv-gke
## The repo needs to be created once:
gcloud artifacts repositories create "${GCP_REPOSITORY_NAME}" \
   --project="${GCP_PROJECT_ID}" \
   --repository-format=docker \
   --location="${GCP_REGION}" \
   --description="My docker repository for awesome images."
gcloud builds submit --tag "${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${GCP_REPOSITORY_NAME}/${GCP_IMAGE_NAME}
```

Create the cluster.

```
gcloud container clusters create-auto ${GCP_IMAGE_NAME} --location ${GCP_REGION}
```

Check the nodes.

```
kubectl get nodes
```

Create the deployment and the service:

```
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
```

Check how the things are going.

```
kubectl get deployments
kubectl get services
kubectl get pods
```


Cleanup the cluster.

```
gcloud container clusters delete ${GCP_IMAGE_NAME} --location ${GCP_REGION}
```

Find the error in the log.

```
kubectl logs ${GCP_IMAGE_NAME}-785598cc9-7tndq
Error: Could not find or load main class com.goo.test.k8s.HttpServer
```

