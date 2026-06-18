#!/bin/bash
# Complete end-to-end test for temporal-service
# Run from the temporal-service root: ./examples/run-test.sh

BASE="http://localhost:8082"
NAMESPACE="default"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

ok()   { echo -e "${GREEN}✓ $1${NC}"; }
info() { echo -e "${BLUE}▶ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
fail() { echo -e "${RED}✗ $1${NC}"; exit 1; }

# ─── 1. Health check ──────────────────────────────────────────────────────────
info "Step 1: Health check"
HEALTH=$(curl -sf "$BASE/actuator/health" | grep -o '"status":"UP"') || fail "Service not running. Start it first (./gradlew bootRun)"
ok "Service is UP"

# ─── 2. Register the simple WorkflowTemplate ─────────────────────────────────
info "\nStep 2: Register hello-world-template (WorkflowTemplate)"
RESP=$(curl -sf -X POST "$BASE/api/v1/workflow-templates/$NAMESPACE" \
  -H "Content-Type: application/x-yaml" \
  --data-binary @examples/test-workflow-template.yaml)
echo "  Response: $RESP"
ok "WorkflowTemplate registered"

# ─── 3. List workflow templates ───────────────────────────────────────────────
info "\nStep 3: List all WorkflowTemplates"
curl -sf "$BASE/api/v1/workflow-templates/$NAMESPACE" | python3 -m json.tool 2>/dev/null || \
  curl -sf "$BASE/api/v1/workflow-templates/$NAMESPACE"
echo ""

# ─── 4. Submit the simple 2-step workflow ────────────────────────────────────
info "\nStep 4: Submit hello-world-run-1 (2 sequential steps)"
WORKFLOW_RESP=$(curl -sf -X POST "$BASE/api/v1/workflows/$NAMESPACE" \
  -H "Content-Type: application/x-yaml" \
  --data-binary @examples/test-workflow.yaml)
echo "  Response: $WORKFLOW_RESP"
WORKFLOW_NAME=$(echo "$WORKFLOW_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('metadata',{}).get('name','hello-world-run-1'))" 2>/dev/null || echo "hello-world-run-1")
ok "Workflow submitted: $WORKFLOW_NAME"

# ─── 5. Poll for status ───────────────────────────────────────────────────────
info "\nStep 5: Polling workflow status (max 60s)..."
for i in $(seq 1 12); do
  sleep 5
  STATUS_RESP=$(curl -sf "$BASE/api/v1/workflows/$NAMESPACE/$WORKFLOW_NAME" 2>/dev/null)
  PHASE=$(echo "$STATUS_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',{}).get('phase','Unknown'))" 2>/dev/null || echo "Unknown")
  echo "  [${i}] Phase: $PHASE"
  if [[ "$PHASE" == "Succeeded" ]]; then
    ok "Workflow SUCCEEDED in $((i*5))s!"
    break
  elif [[ "$PHASE" == "Failed" || "$PHASE" == "Error" ]]; then
    warn "Workflow ended with phase: $PHASE"
    echo "  Full response: $STATUS_RESP"
    break
  fi
done

# ─── 6. Register multi-step ETL template ─────────────────────────────────────
info "\nStep 6: Register data-pipeline-template (3-step ETL)"
curl -sf -X POST "$BASE/api/v1/workflow-templates/$NAMESPACE" \
  -H "Content-Type: application/x-yaml" \
  --data-binary @examples/test-multi-step-workflow.yaml > /dev/null
ok "data-pipeline-template registered"

# ─── 7. Submit multi-step ETL workflow ───────────────────────────────────────
info "\nStep 7: Submit data-pipeline-run-1 (Extract → Transform → Load)"
PIPELINE_RESP=$(curl -sf -X POST "$BASE/api/v1/workflows/$NAMESPACE" \
  -H "Content-Type: application/x-yaml" \
  --data-binary @examples/test-multi-step-run.yaml)
echo "  Response: $PIPELINE_RESP"
PIPELINE_NAME=$(echo "$PIPELINE_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('metadata',{}).get('name','data-pipeline-run-1'))" 2>/dev/null || echo "data-pipeline-run-1")
ok "Pipeline submitted: $PIPELINE_NAME"

# ─── 8. Check K8s jobs ────────────────────────────────────────────────────────
info "\nStep 8: K8s Jobs created by temporal-service"
echo "  Listing jobs in namespace $NAMESPACE:"
kubectl get jobs -n $NAMESPACE --sort-by=.metadata.creationTimestamp 2>/dev/null | tail -10

# ─── 9. List all workflows ────────────────────────────────────────────────────
info "\nStep 9: List all workflows"
curl -sf "$BASE/api/v1/workflows/$NAMESPACE" | python3 -m json.tool 2>/dev/null || \
  curl -sf "$BASE/api/v1/workflows/$NAMESPACE"
echo ""

# ─── 10. List workflows filtered by label ────────────────────────────────────
info "\nStep 10: Filter workflows by label type=test"
curl -sf "$BASE/api/v1/workflows/$NAMESPACE?listOptions.labelSelector=type=test" | python3 -m json.tool 2>/dev/null || \
  curl -sf "$BASE/api/v1/workflows/$NAMESPACE?listOptions.labelSelector=type=test"
echo ""

ok "\n=== Test complete. Open http://localhost:8080 to see workflows in Temporal UI ==="
