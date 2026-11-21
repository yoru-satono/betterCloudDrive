FROM apache/airflow:3.4.0-python3.12
USER airflow
WORKDIR /opt/warehouse
COPY --chown=airflow:root pyproject.toml uv.lock /opt/warehouse/
RUN pip install --no-cache-dir uv==0.13.9 \
    && uv sync --frozen --no-dev --no-install-project \
    && mkdir -p /opt/airflow/warehouse-auth /opt/airflow/warehouse-artifacts
COPY --chown=airflow:root . /opt/warehouse
RUN uv sync --frozen --no-dev
ENV PYTHONPATH=/opt/warehouse
