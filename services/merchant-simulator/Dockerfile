FROM python:3.12-slim
WORKDIR /app
COPY services/python-common /common
RUN pip install --no-cache-dir -r /common/requirements.txt
COPY services/merchant-simulator/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/merchant-simulator .
ENV PYTHONPATH=/common:/app
RUN useradd --system --uid 10001 app && mkdir -p /data && chown app /data
USER app
CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8000","--no-access-log"]
