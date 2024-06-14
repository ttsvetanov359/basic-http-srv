#!/bin/bash
RC=
JAVA=
declare -a CPARGS
declare -a COMMAND

is_dry_run=0
if [[ "$1" = "--dry-run" ]]; then
    is_dry_run=1
fi

# find the classpath
function findJdk() {
    local output rc
    local jdks jdk
    declare -a jdks
    jdks=("java" "${JAVA_HOME}/bin/java")

    for jdk in "${jdks[@]}"; do
        output=$(${jdk} -version 2>&1)
        rc=$?
        if [[ ${rc} -ne 0 ]]; then
            echo "ERROR JDK not found: '${output}'"
        else
            echo "JDK version is $(echo "${output}" | tr '\012' '#')"
            JAVA=${jdk}
            break
        fi
    done
    return ${rc}
}

function defineClasspath() {
    local output rc cpath
    output=$(find /usr/local/lib -type f -name "*.jar")
    rc=$?
    if [[ ${rc} -ne 0 ]]; then
        echo >&2 "ERROR Unable to find jar files: '${output}'"
    else
        cpath=$(echo "${output}" | tr '\012' ':')
        cpath="${cpath}."
        CPARGS=("-classpath" "${cpath}")
        echo "CPARGS = (${CPARGS[*]})"
    fi
    return ${rc}
}

function javaCommand() {
    if [[ -z "${JVM_MAX_HEAP_MB}" ]]; then
        JVM_MAX_HEAP_MB=256
    fi
    COMMAND=("${JAVA}" -Dgcplogging
        "-Xms${JVM_MAX_HEAP_MB}m" "-Xmx${JVM_MAX_HEAP_MB}m"
        -Djava.util.logging.config.file=/usr/local/lib/logging.properties)
    COMMAND+=("${CPARGS[@]}")
    COMMAND+=(
        com.goo.test.k8s.HttpServer
        --enable-monitor
        -p "${HTTPSRV_PORT}"
        -b "${HTTPSRV_BACKLOG}"
        -t "${HTTPSRV_RUNTIME}")
    return 0
}

function exitOnNoDryRun() {
    if [[ ${1} -ne 0 ]] && [[ ${is_dry_run} -eq 0 ]]; then return "${1}"; fi
}

GKE_MONITORING_POD_NAME=$(hostname)
export GKE_MONITORING_POD_NAME

findJdk || exitOnNoDryRun $? || exit $?
defineClasspath || exitOnNoDryRun $? || exit $?
javaCommand || exitOnNoDryRun $? || exit $?

echo "Command line: '${COMMAND[*]}'"
if [[ ${is_dry_run} -eq 1 ]]; then exit 0; fi


echo "GOOGLE_APPLICATION_CREDENTIALS='${GOOGLE_APPLICATION_CREDENTIALS}'"
echo "GKE_MONITORING_POD_NAME='${GKE_MONITORING_POD_NAME}'"

echo "${COMMAND[@]}" | sh
RC=$?
if [[ ${RC} -ne 0 ]]; then
    echo >&2 "ERROR Java program exited with ${RC}"
fi
exit ${RC}
