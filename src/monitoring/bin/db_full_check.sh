echo CROMWELL_GOOGLE_PROJECT is ${CROMWELL_GOOGLE_PROJECT}
echo CROMWELL_CLOUDSQL_INSTANCE is ${CROMWELL_CLOUDSQL_INSTANCE}
echo CROMWELL_MAXIMUM_DATABASE_SIZE_TIB is ${CROMWELL_MAXIMUM_DATABASE_SIZE_TIB}
echo CROMWELL_DATABASE_FORECAST_LIMIT_MONTHS is ${CROMWELL_DATABASE_FORECAST_LIMIT_MONTHS}
echo METRICS_ACCESS_TOKEN is ${METRICS_ACCESS_TOKEN}
pip install numpy requests python-dateutil

python db_full_check.py