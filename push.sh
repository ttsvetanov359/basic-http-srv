#!/bin/bash
if [[ $# -ne 1 ]] ; then
  >&2 echo "Mandatory arguments: <gcs_url>"
  exit 1
fi
AR=basic-http-srv.tar.gz
tar -zcf ${AR} src/* pom.xml start.sh service_account.json
gsutil cp Dockerfile deployment.yaml service.yaml "${AR}" "${1}" ; rc=$?
rm ${AR}
exit ${rc}
