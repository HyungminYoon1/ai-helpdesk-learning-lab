FROM public.ecr.aws/amazonlinux/amazonlinux:2023@sha256:18c71e6a20f8b0834b002b6002156a84eea76162d5adea72d2ea8b86800eaf91 AS client

# Build locally. Do not install a DB server or add Internet access to the VPC shell.
RUN dnf install -y --releasever=2023.12.20260930 tar gzip postgresql17-17.11-1.amzn2023.0.1 \
    && rpm -q postgresql17 postgresql17-private-libs \
    && test "$(psql --version)" = 'psql (PostgreSQL) 17.11' \
    && mkdir -p /client/bin /client/lib /artifact \
    && cp /usr/bin/psql /client/bin/psql.real \
    && cp -L /usr/lib64/libpq.so.private17-5 /client/lib/libpq.so.private17-5 \
    && cp /usr/share/doc/postgresql17/COPYRIGHT /client/COPYRIGHT

COPY psql17-wrapper.sh /client/bin/psql
RUN chmod 0555 /client/bin/psql /client/bin/psql.real /client/lib/libpq.so.private17-5 \
    && chmod 0444 /client/COPYRIGHT \
    && tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
       -czf /artifact/psql17.tar.gz -C /client \
       bin/psql bin/psql.real lib/libpq.so.private17-5 COPYRIGHT

FROM scratch
COPY --from=client /artifact/ /
