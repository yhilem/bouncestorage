FROM dockerfile/java:oracle-java8
COPY target/bounce /data/
COPY src/test/resources/bounce.properties /data/
RUN mkdir -p /tmp/blobstore
EXPOSE 8080 9000 9001
CMD []
ENTRYPOINT [ "./bounce", "--properties", "bounce.properties" ]
