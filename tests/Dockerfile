FROM python:3.12-slim
WORKDIR /tests
RUN pip install --no-cache-dir pytest==8.3.5 httpx==0.28.1 psycopg[binary]==3.2.9
COPY tests .
CMD ["pytest","-q","-s","test_scenarios.py"]
