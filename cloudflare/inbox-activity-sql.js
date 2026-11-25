// Shared exact expression for runtime ordering and the reviewed partial index.
const inboxSqlMillis=field=>`CASE
  WHEN json_type(f.document_json,'$.${field}') IN ('integer','real')
    AND abs(CAST(json_extract(f.document_json,'$.${field}') AS REAL))<=1.7976931348623157e308
    THEN json_extract(f.document_json,'$.${field}')
  WHEN json_extract(f.document_json,'$.${field}.__type')='timestamp'
    AND json_type(f.document_json,'$.${field}.millis') IN ('integer','real')
    AND abs(CAST(json_extract(f.document_json,'$.${field}.millis') AS REAL))<=1.7976931348623157e308
    THEN CASE WHEN abs(CAST(json_extract(f.document_json,'$.${field}.millis') AS REAL))<9.223372036854776e18
      THEN CAST(json_extract(f.document_json,'$.${field}.millis') AS INTEGER)
      ELSE json_extract(f.document_json,'$.${field}.millis') END ELSE 0 END`;
export const inboxSqlActivity=`COALESCE(${['lastMessageTime','lastMessageTimeMs','lastMessageAt'].map(f=>`NULLIF((${inboxSqlMillis(f)}),0)`).join(',')},0)`;
