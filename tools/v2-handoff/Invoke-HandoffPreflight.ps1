[CmdletBinding()]
param(
    [string]$ContractPath,
    [string]$RepoRoot,
    [ValidateSet("next", "padQa", "none")]
    [string]$Prompt = "next",
    [switch]$EmitPrompt
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8Output = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8Output
$OutputEncoding = $utf8Output

$expectedActiveTokenPointers = [string[]]@(
    "ACTIVE_SECTION"
)
$prePendingActiveRequiredTokens = [string[]]@(
    "HANDOFF_BOOTSTRAP_V2",
    "SUBAGENT_FIRST=YES",
    "ONE_PHASE_PER_FRESH_AGENT=YES",
    "NO_REUSE_SUBAGENT=YES",
    "ROOT_INTEGRATION_ONLY=YES",
    "DEVICE_WINDOW_MINUTES=10",
    "DEVICE_ORDER=Pad->K60",
    "PRE_REVIEW_PENDING",
    "PRE_REVIEW_PASS_AWAITING_AUDIT",
    "ROUND8_PASS_PRE_STATE_REPAIRED_AWAITING_FRESH_ROUND9_REVIEW",
    "BLOCKED_PENDING_ROUND9_REVIEW",
    "validation.handoffPhase=PRE_HANDOFF_AUDIT",
    "INDEPENDENT_INTEGRATION_REVIEW_PASS=BLOCKED",
    "PREFLIGHT_PASS",
    "PRE_HANDOFF_AUDIT_PASS=BLOCKED",
    "QA_DISPATCHABLE=BLOCKED",
    "ROUND8-P1-01",
    "ROUND4-P1-01",
    "ROUND5-P1-01",
    "ROUND6-P1-01",
    "ROUND7-P1-01",
    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND7_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND6_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND5_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER",
    "POST_HANDOFF_AUDIT_READY",
    "auditReceipt",
    "CORE_STEPS_0",
    "PRODUCT_P0_P3_NOT_ASSESSED",
    "K60_NOT_RUN",
    "ACTIVE=0",
    "22",
    "31",
    "2984",
    "25/25",
    "86/86",
    "96/96",
    "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
    "ROUND1-P1-01",
    "ROUND1-P2-01",
    "ROUND1-P2-02",
    "ROUND2-P2-01",
    "ROOT_INTEGRATION",
    "IMPLEMENTATION_OWNER",
    "TOOL_REPAIR_OWNER",
    "DOC_PROTOCOL_OWNER",
    "HANDOFF_POST_AUDIT_PHASE_REPAIR_OWNER",
    "HANDOFF_POST_SCOPE_REPAIR_OWNER",
    "HANDOFF_ANCHOR_HARDENING_REPAIR_OWNER",
    "HANDOFF_ROLE_SCOPE_HARDENING_REPAIR_OWNER",
    "INTEGRATION_REVIEW_OWNER",
    "ROUND2_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND5_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND6_READONLY_INTEGRATION_REVIEW_OWNER",
    "PRE_HANDOFF_AUDIT_OWNER",
    "PAD_QA_OWNER",
    "K60_QA_OWNER",
    "INDEPENDENT_INTEGRATION_REVIEW_PASS",
    "PRE_HANDOFF_AUDIT_PASS",
    "QA_DISPATCHABLE",
    "READ_ONLY_PREFLIGHT",
    "tools/v2-handoff/Invoke-HandoffPreflight.ps1"
)
$preReviewPassActiveRequiredTokens = [string[]]@(
    "HANDOFF_BOOTSTRAP_V2",
    "SUBAGENT_FIRST=YES",
    "ONE_PHASE_PER_FRESH_AGENT=YES",
    "NO_REUSE_SUBAGENT=YES",
    "ROOT_INTEGRATION_ONLY=YES",
    "DEVICE_WINDOW_MINUTES=10",
    "DEVICE_ORDER=Pad->K60",
    "PRE_REVIEW_PENDING",
    "PRE_REVIEW_PASS_AWAITING_AUDIT",
    "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT",
    "validation.handoffPhase=PRE_HANDOFF_AUDIT",
    "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
    "PREFLIGHT_PASS",
    "PRE_HANDOFF_AUDIT_PASS=BLOCKED",
    "QA_DISPATCHABLE=BLOCKED",
    "ROUND8-P1-01",
    "ROUND4-P1-01",
    "ROUND5-P1-01",
    "ROUND6-P1-01",
    "ROUND7-P1-01",
    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND7_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND6_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND5_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER",
    "POST_HANDOFF_AUDIT_READY",
    "auditReceipt",
    "CORE_STEPS_0",
    "PRODUCT_P0_P3_NOT_ASSESSED",
    "K60_NOT_RUN",
    "ACTIVE=0",
    "22",
    "31",
    "2984",
    "25/25",
    "86/86",
    "96/96",
    "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
    "ROUND1-P1-01",
    "ROUND1-P2-01",
    "ROUND1-P2-02",
    "ROUND2-P2-01",
    "ROOT_INTEGRATION",
    "IMPLEMENTATION_OWNER",
    "TOOL_REPAIR_OWNER",
    "DOC_PROTOCOL_OWNER",
    "HANDOFF_POST_AUDIT_PHASE_REPAIR_OWNER",
    "HANDOFF_POST_SCOPE_REPAIR_OWNER",
    "HANDOFF_ANCHOR_HARDENING_REPAIR_OWNER",
    "HANDOFF_ROLE_SCOPE_HARDENING_REPAIR_OWNER",
    "INTEGRATION_REVIEW_OWNER",
    "ROUND2_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND5_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND6_READONLY_INTEGRATION_REVIEW_OWNER",
    "PRE_HANDOFF_AUDIT_OWNER",
    "PAD_QA_OWNER",
    "K60_QA_OWNER",
    "INDEPENDENT_INTEGRATION_REVIEW_PASS",
    "PRE_HANDOFF_AUDIT_PASS",
    "QA_DISPATCHABLE",
    "READ_ONLY_PREFLIGHT",
    "tools/v2-handoff/Invoke-HandoffPreflight.ps1"
)
$preActiveRequiredTokensByState = @{
    PRE_REVIEW_PENDING = $prePendingActiveRequiredTokens
    PRE_REVIEW_PASS_AWAITING_AUDIT = $preReviewPassActiveRequiredTokens
}
$postActiveRequiredTokens = [string[]]@(
    "HANDOFF_BOOTSTRAP_V2",
    "SUBAGENT_FIRST=YES",
    "ONE_PHASE_PER_FRESH_AGENT=YES",
    "NO_REUSE_SUBAGENT=YES",
    "ROOT_INTEGRATION_ONLY=YES",
    "DEVICE_WINDOW_MINUTES=10",
    "DEVICE_ORDER=Pad->K60",
    "POST_HANDOFF_AUDIT_READY",
    "validation.handoffPhase=POST_HANDOFF_AUDIT_READY",
    "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
    "PRE_HANDOFF_AUDIT_PASS=PASS",
    "QA_DISPATCHABLE=PASS",
    "ROUND4-P1-01",
    "ROUND5-P1-01",
    "ROUND6-P1-01",
    "ROUND7-P1-01",
    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND7_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND5_READONLY_INTEGRATION_REVIEW_OWNER",
    "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER",
    "HANDOFF_ANCHOR_HARDENING_REPAIR_OWNER",
    "immutableStateSha256",
    "postMutationPointerAllowlist",
    "auditReceipt",
    "CORE_STEPS_0",
    "PRODUCT_P0_P3_NOT_ASSESSED",
    "K60_NOT_RUN",
    "ACTIVE=0",
    "31",
    "2984",
    "25/25",
    "96/96",
    "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
    "c4a2d2a1",
    "ROOT_INTEGRATION",
    "HANDOFF_POST_AUDIT_PHASE_REPAIR_OWNER",
    "PRE_HANDOFF_AUDIT_OWNER",
    "PAD_QA_OWNER",
    "K60_QA_OWNER",
    "READ_ONLY_PREFLIGHT",
    "tools/v2-handoff/Invoke-HandoffPreflight.ps1"
)
$postQaDeferredActiveRequiredTokens = [string[]]@(
    "HANDOFF_BOOTSTRAP_V2",
    "SUBAGENT_FIRST=YES",
    "ONE_PHASE_PER_FRESH_AGENT=YES",
    "NO_REUSE_SUBAGENT=YES",
    "ROOT_INTEGRATION_ONLY=YES",
    "DEVICE_WINDOW_MINUTES=10",
    "DEVICE_ORDER=Pad->K60",
    "POST_HANDOFF_AUDIT_READY",
    "validation.handoffPhase=POST_QA_RUNTIME_DEFERRED",
    "RUNTIME_QA_DEFERRED_TO_G2",
    "G1_STATIC_CLOSURE_IN_PROGRESS",
    "G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS",
    "V2-D209",
    "G2_RUNTIME_CONTRACT_OWNER",
    "QA_V9_BLOCKED_TOOL_NONCONVERGENCE",
    "PRODUCT_P0_P3_NOT_ASSESSED",
    "K60_LOCKED",
    "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
    "PRE_HANDOFF_AUDIT_PASS=PASS",
    "QA_DISPATCHABLE=DEFERRED_TO_G2",
    "V2-D208",
    "ROUND8-P1-01",
    "ROUND7-P1-01",
    "ROUND6-P1-01",
    "ROUND5-P1-01",
    "ROUND4-P1-01",
    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
    "PRE_HANDOFF_AUDIT_OWNER",
    "PAD_QA_OWNER",
    "K60_QA_OWNER",
    "POST_HANDOFF_AUDIT_READY",
    "auditReceipt",
    "CORE_STEPS_1",
    "PAD_10_MIN_PARTIAL_TOOL_BLOCKED",
    "K60_NOT_REGISTERED_NOT_RUN",
    "ACTIVE=0",
    "31",
    "2984",
    "25/25",
    "96/96",
    "104/104",
    "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
    "qa-v9",
    "READ_ONLY_PREFLIGHT",
    "tools/v2-handoff/Invoke-HandoffPreflight.ps1"
)
$expectedHistoricalAuditDeviceLockSha256 =
    "8335b81832a39b96356db1532b17216f05c1c07d273953b187e775227f610428"
$expectedHistoricalAuditMemoryIndexSha256 =
    "22abc8da4a877ff401487414b375bf70b17a58d4f3a64ba10a02dc09c9f223d5"
$expectedRequiredTextAnchorPointers = @(
    [pscustomobject]@{ id = "AGENTS"; path = "AGENTS.md" },
    [pscustomobject]@{ id = "PROJECT_RULES"; path = "docs/PROJECT_RULES.md" },
    [pscustomobject]@{ id = "PROJECT_PLAN"; path = "docs/PROJECT_PLAN.md" },
    [pscustomobject]@{ id = "TASKS"; path = "docs/TASKS.md" },
    [pscustomobject]@{ id = "LESSONS"; path = "docs/LESSONS.md" }
)
$prePendingAnchorTokenSets = @{
    AGENTS = [string[]]@(
        "HANDOFF_BOOTSTRAP_V2",
        "Invoke-HandoffPreflight.ps1",
        "FAIL_CLOSED",
        "DEVICE_WINDOW_MINUTES=10",
        "PRE_REVIEW_PENDING",
        "PRE_REVIEW_PASS_AWAITING_AUDIT",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "B11",
        "PRE_HANDOFF_AUDIT",
        "POST_HANDOFF_AUDIT_READY",
        "auditReceipt"
    )
    PROJECT_RULES = [string[]]@(
        "R029",
        "R030",
        "R031",
        "R032",
        "HANDOFF_BOOTSTRAP_V2",
        "DEVICE_ORDER=Pad->K60",
        "PRE_REVIEW_PENDING",
        "PRE_REVIEW_PASS_AWAITING_AUDIT",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "auditReceipt"
    )
    PROJECT_PLAN = [string[]]@(
        "ROUND8_PASS_PRE_STATE_REPAIRED_AWAITING_FRESH_ROUND9_REVIEW",
        "INDEPENDENT_INTEGRATION_REVIEW_PASS=BLOCKED",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND8-P1-01",
        "ROUND7-P1-01",
        "ROUND6-P1-01",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "25/25",
        "86/86",
        "96/96",
        "HANDOFF_BOOTSTRAP_V2",
        "c4a2d2a1"
    )
    TASKS = [string[]]@(
        "ROUND8_PASS_PRE_STATE_REPAIRED_AWAITING_FRESH_ROUND9_REVIEW",
        "INDEPENDENT_INTEGRATION_REVIEW_PASS=BLOCKED",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND8-P1-01",
        "ROUND7-P1-01",
        "ROUND6-P1-01",
        "PAD_10_MIN_CORE_NOT_RUN",
        "K60_NOT_REGISTERED_NOT_RUN",
        "PRODUCT_P0_P3_NOT_ASSESSED",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "25/25",
        "86/86",
        "96/96"
    )
    LESSONS = [string[]]@(
        "L026",
        "L024",
        "L018",
        "L019",
        "L020",
        "L021",
        "L022",
        "L023",
        "HANDOFF_BOOTSTRAP_V2",
        "NO_REUSE_SUBAGENT",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
    )
}
$preReviewPassAnchorTokenSets = @{
    AGENTS = $prePendingAnchorTokenSets.AGENTS
    PROJECT_RULES = $prePendingAnchorTokenSets.PROJECT_RULES
    PROJECT_PLAN = [string[]]@(
        "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT",
        "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND8-P1-01",
        "ROUND7-P1-01",
        "ROUND6-P1-01",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "25/25",
        "86/86",
        "96/96",
        "HANDOFF_BOOTSTRAP_V2",
        "c4a2d2a1"
    )
    TASKS = [string[]]@(
        "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT",
        "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND8-P1-01",
        "ROUND7-P1-01",
        "ROUND6-P1-01",
        "PAD_10_MIN_CORE_NOT_RUN",
        "K60_NOT_REGISTERED_NOT_RUN",
        "PRODUCT_P0_P3_NOT_ASSESSED",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "25/25",
        "86/86",
        "96/96"
    )
    LESSONS = $prePendingAnchorTokenSets.LESSONS
}
$preAnchorTokenSetsByState = @{
    PRE_REVIEW_PENDING = $prePendingAnchorTokenSets
    PRE_REVIEW_PASS_AWAITING_AUDIT = $preReviewPassAnchorTokenSets
}
$postAnchorTokenSets = @{
    AGENTS = $prePendingAnchorTokenSets.AGENTS
    PROJECT_RULES = $prePendingAnchorTokenSets.PROJECT_RULES
    PROJECT_PLAN = $preReviewPassAnchorTokenSets.PROJECT_PLAN
    TASKS = [string[]]@(
        "POST_HANDOFF_AUDIT_READY",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER",
        "ROUND7-P1-01",
        "ROUND6-P1-01",
        "PAD_10_MIN_CORE_NOT_RUN",
        "K60_NOT_REGISTERED_NOT_RUN",
        "PRODUCT_P0_P3_NOT_ASSESSED",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "25/25",
        "86/86",
        "96/96"
    )
    LESSONS = $prePendingAnchorTokenSets.LESSONS
}
$postQaDeferredAnchorTokenSets = @{
    AGENTS = [string[]]@(
        $prePendingAnchorTokenSets.AGENTS +
        @(
            "RUNTIME_QA_DEFERRED_TO_G2",
            "G1_STATIC_CLOSURE_IN_PROGRESS",
            "G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS",
            "V2-D209",
            "G2_RUNTIME_CONTRACT_OWNER",
            "validation.handoffPhase=POST_QA_RUNTIME_DEFERRED",
            "QA_DISPATCHABLE=DEFERRED_TO_G2",
            "V2-D208",
            "104/104"
        )
    )
    PROJECT_RULES = $prePendingAnchorTokenSets.PROJECT_RULES
    PROJECT_PLAN = [string[]]@(
        "RUNTIME_QA_DEFERRED_TO_G2",
        "G1_STATIC_CLOSURE_IN_PROGRESS",
        "G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS",
        "V2-D209",
        "G2_RUNTIME_CONTRACT_OWNER",
        "QA_V9_BLOCKED_TOOL_NONCONVERGENCE",
        "PRODUCT_P0_P3_NOT_ASSESSED",
        "K60_LOCKED",
        "V2-D208",
        "ROUND8-P1-01",
        "ROUND7-P1-01",
        "ROUND6-P1-01",
        "25/25",
        "86/86",
        "96/96",
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4",
        "HANDOFF_BOOTSTRAP_V2",
        "c4a2d2a1"
    )
    TASKS = [string[]]@(
        $postAnchorTokenSets.TASKS +
        @(
            "RUNTIME_QA_DEFERRED_TO_G2",
            "G1_STATIC_CLOSURE_IN_PROGRESS",
            "G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS",
            "V2-D209",
            "G2_RUNTIME_CONTRACT_OWNER",
            "QA_V9_BLOCKED_TOOL_NONCONVERGENCE",
            "PRODUCT_P0_P3_NOT_ASSESSED",
            "K60_LOCKED",
            "V2-D208"
        )
    )
    LESSONS = $prePendingAnchorTokenSets.LESSONS
}
$anchorForbiddenTokenSets = @{
    AGENTS = [string[]]@()
    PROJECT_RULES = [string[]]@()
    PROJECT_PLAN = [string[]]@(
        "Pad QA-v6 native crash P1 修复已授权",
        "HANDOFF_BOOTSTRAP_V2_BUILT_PREFLIGHT_BLOCKED_A_TOOLS_DRIFT",
        "10 个 ignored 新文件待 audit PASS 后由 root 显式 force-add"
    )
    TASKS = [string[]]@(
        "BLOCKED_PENDING_TOOL_REPAIR_AND_CONTRACT_REFRESH",
        "d8e98a90b250bafb1bb48d62f315041fd0f67653d8d81c4650aca9fb617d0bfb",
        "a7e5def25343bb96c15b76398b421115de60f40824698d67a682f3c4b7908901",
        "BLOCKED_PENDING_INTEGRATION_REVIEW_AND_PRE_HANDOFF_AUDIT",
        "BLOCKED_PENDING_ROUND2_INTEGRATION_REVIEW_AND_PRE_HANDOFF_AUDIT",
        "ROUND3_INTEGRATION_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT",
        "PRE_HANDOFF_AUDIT_BLOCKED_POST_AUDIT_PHASE_REPAIRED_AWAITING_FRESH_ROUND4_REVIEW"
    )
    LESSONS = [string[]]@()
}

$handoffProtocolScopes = [string[]]@(
    "AGENTS.md",
    "docs/PROJECT_RULES.md",
    "docs/PROJECT_PLAN.md",
    "docs/V2_PLAN.md",
    "docs/TASKS.md",
    "docs/LESSONS.md",
    "tools/v2-handoff/**"
)
$handoffRepairScopes = [string[]]@(
    "AGENTS.md",
    "docs/PROJECT_RULES.md",
    "docs/PROJECT_PLAN.md",
    "docs/V2_PLAN.md",
    "docs/TASKS.md",
    "docs/LESSONS.md",
    "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json",
    "tools/v2-handoff/Invoke-HandoffPreflight.ps1",
    "tools/v2-handoff/Test-HandoffPreflight.ps1"
)
$roleCategoryPatterns = [ordered]@{
    ROOT = "^ROOT_INTEGRATION$"
    HANDOFF_PROTOCOL = "^(?:HANDOFF_PROTOCOL_OWNER|DOC_PROTOCOL_OWNER|HANDOFF_(?:POST_AUDIT_PHASE|POST_SCOPE|ANCHOR_HARDENING|SOURCE_PRE_VALIDATION|ROLE_SCOPE_HARDENING)_REPAIR_OWNER)$"
    IMPLEMENTATION = "^IMPLEMENTATION_OWNER$"
    TOOL_REPAIR = "^TOOL_REPAIR_OWNER$"
    READ_ONLY_REVIEW_AUDIT = "^(?:INTEGRATION_REVIEW_OWNER|ROUND[2-9]_READONLY_INTEGRATION_REVIEW_OWNER|PRE_HANDOFF_AUDIT_OWNER)$"
    PAD_QA = "^PAD_QA_OWNER$"
    K60_QA = "^K60_QA_OWNER$"
}
$roleWriteScopeMarkers = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
[void]$roleWriteScopeMarkers.Add(
    "INTEGRATION_METADATA_AND_TASK_ROUTING_ONLY"
)
[void]$roleWriteScopeMarkers.Add(
    "TASK_CARD_SCOPED_PRODUCT_FILES_AND_TESTS_ONLY"
)
$productionWriteScopeRoots = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
foreach ($productionRoot in @(
    "app",
    "core",
    "data",
    "player",
    "modes",
    "settings",
    "setup",
    "buildSrc"
)) {
    [void]$productionWriteScopeRoots.Add($productionRoot)
}
$immutableRoleSpecs = @(
    [pscustomobject]@{
        id = "ROOT_INTEGRATION"
        category = "ROOT"
        kind = "ROOT"
        freshness = "THREAD_BOUND"
        phaseLimit = "ROOT_INTEGRATION_ONLY"
        reuse = "NOT_APPLICABLE"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@(
            "INTEGRATION_METADATA_AND_TASK_ROUTING_ONLY"
        )
    },
    [pscustomobject]@{
        id = "HANDOFF_PROTOCOL_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffProtocolScopes
    },
    [pscustomobject]@{
        id = "IMPLEMENTATION_OWNER"
        category = "IMPLEMENTATION"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "IMPLEMENTATION_SELF_TEST_ONLY_IF_AUTHORIZED"
        writeScope = [string[]]@(
            "TASK_CARD_SCOPED_PRODUCT_FILES_AND_TESTS_ONLY"
        )
    },
    [pscustomobject]@{
        id = "TOOL_REPAIR_OWNER"
        category = "TOOL_REPAIR"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@(
            ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools/**"
        )
    },
    [pscustomobject]@{
        id = "DOC_PROTOCOL_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffProtocolScopes
    },
    [pscustomobject]@{
        id = "HANDOFF_POST_AUDIT_PHASE_REPAIR_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffRepairScopes
    },
    [pscustomobject]@{
        id = "HANDOFF_POST_SCOPE_REPAIR_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffRepairScopes
    },
    [pscustomobject]@{
        id = "HANDOFF_ANCHOR_HARDENING_REPAIR_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffRepairScopes
    },
    [pscustomobject]@{
        id = "HANDOFF_SOURCE_PRE_VALIDATION_REPAIR_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffRepairScopes
    },
    [pscustomobject]@{
        id = "HANDOFF_ROLE_SCOPE_HARDENING_REPAIR_OWNER"
        category = "HANDOFF_PROTOCOL"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = $handoffRepairScopes
    },
    [pscustomobject]@{
        id = "INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND2_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND4_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND5_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND6_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND7_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND8_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "PRE_HANDOFF_AUDIT_OWNER"
        category = "READ_ONLY_REVIEW_AUDIT"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "FORBIDDEN"
        writeScope = [string[]]@()
    },
    [pscustomobject]@{
        id = "PAD_QA_OWNER"
        category = "PAD_QA"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "PAD_ONLY"
        writeScope = [string[]]@(
            ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v9/**"
        )
    },
    [pscustomobject]@{
        id = "K60_QA_OWNER"
        category = "K60_QA"
        kind = "SPECIALIST"
        freshness = "FRESH_PER_PHASE"
        phaseLimit = "ONE"
        reuse = "FORBIDDEN"
        deviceAccess = "K60_ONLY_AFTER_PAD_PASS"
        writeScope = [string[]]@(
            ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v10/**"
        )
    }
)
$expectedRoleIds = [string[]]@(
    $immutableRoleSpecs | ForEach-Object { $_.id }
)

$validationErrors = [System.Collections.Generic.List[string]]::new()

function Add-PreflightError {
    param([Parameter(Mandatory)][string]$Code)
    $validationErrors.Add($Code)
}

function Get-ContractProperty {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory)][string]$PropertyName
    )

    if ($null -eq $ObjectValue) {
        return $null
    }

    $propertyInfo = $ObjectValue.PSObject.Properties[$PropertyName]
    if ($null -eq $propertyInfo) {
        return $null
    }

    return $propertyInfo.Value
}

function Test-NonEmptyString {
    param([AllowNull()][object]$Value)
    return ($Value -is [string] -and -not [string]::IsNullOrWhiteSpace($Value))
}

function Test-StringArray {
    param([AllowNull()][object]$Value)

    if ($Value -isnot [System.Array]) {
        return $false
    }

    foreach ($item in $Value) {
        if (-not (Test-NonEmptyString -Value $item)) {
            return $false
        }
    }

    return $true
}

function Test-CanonicalRoleWriteScope {
    param([AllowNull()][object]$Value)

    if (
        $Value -isnot [string] -or
        [string]::IsNullOrEmpty($Value) -or
        $Value -cne $Value.Trim()
    ) {
        return $false
    }
    if ($roleWriteScopeMarkers.Contains($Value)) {
        return $true
    }
    if ([regex]::IsMatch($Value, "[\x00-\x20\\:?`"<>|]")) {
        return $false
    }
    if ($Value.StartsWith("/", [System.StringComparison]::Ordinal)) {
        return $false
    }

    $segments = $Value.Split("/")
    if ($segments.Count -eq 0) {
        return $false
    }
    for ($segmentIndex = 0; $segmentIndex -lt $segments.Count; $segmentIndex++) {
        $segment = $segments[$segmentIndex]
        if (
            [string]::IsNullOrEmpty($segment) -or
            $segment -ceq "." -or
            $segment -ceq ".."
        ) {
            return $false
        }
        if ($segment -ceq "**") {
            if ($segmentIndex -ne $segments.Count - 1) {
                return $false
            }
            continue
        }
        if (
            $segment.Contains("*") -or
            -not [regex]::IsMatch(
                $segment,
                "^[A-Za-z0-9._-]+$",
                [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
            )
        ) {
            return $false
        }
    }
    return $true
}

function Test-ProductionRoleWriteScope {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Category
    )

    if ($Category -ceq "IMPLEMENTATION") {
        return $false
    }
    if ($roleWriteScopeMarkers.Contains($Value)) {
        return $false
    }
    return $productionWriteScopeRoots.Contains($Value.Split("/")[0])
}

function Assert-RoleDefinition {
    param(
        [AllowNull()][object]$RoleObject,
        [Parameter(Mandatory)][object]$ExpectedSpec,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    $roleId = [string](Get-ContractProperty $RoleObject "id")
    $requiredRoleProperties = @(
        "id",
        "kind",
        "identity",
        "freshness",
        "phaseLimit",
        "reuse",
        "allowedActions",
        "writeScope",
        "deviceAccess",
        "deliverable",
        "stopAfter"
    )
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $RoleObject `
        -ExpectedProperties $requiredRoleProperties)) {
        Add-PreflightError -Code "$ErrorPrefix-SCHEMA-INVALID-$roleId"
        if ($null -eq $RoleObject) {
            return
        }
    }

    if ($roleId -cne $ExpectedSpec.id) {
        Add-PreflightError -Code "$ErrorPrefix-ID-DRIFT-$roleId"
    }
    $categoryPattern = $roleCategoryPatterns[$ExpectedSpec.category]
    if (
        [string]::IsNullOrWhiteSpace($categoryPattern) -or
        -not [regex]::IsMatch(
            $roleId,
            $categoryPattern,
            [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
        )
    ) {
        Add-PreflightError -Code "$ErrorPrefix-CATEGORY-INVALID-$roleId"
    }
    foreach ($fieldName in @(
        "kind",
        "freshness",
        "phaseLimit",
        "reuse",
        "deviceAccess"
    )) {
        if (
            [string](Get-ContractProperty $RoleObject $fieldName) -cne
            [string]$ExpectedSpec.$fieldName
        ) {
            Add-PreflightError -Code "$ErrorPrefix-$fieldName-INVALID-$roleId"
        }
    }
    foreach ($fieldName in @("identity", "deliverable", "stopAfter")) {
        if (-not (Test-NonEmptyString -Value (
            Get-ContractProperty $RoleObject $fieldName
        ))) {
            Add-PreflightError -Code "$ErrorPrefix-$fieldName-INVALID-$roleId"
        }
    }
    if (-not (Test-StringArray -Value (
        Get-ContractProperty $RoleObject "allowedActions"
    ))) {
        Add-PreflightError -Code "$ErrorPrefix-ACTIONS-INVALID-$roleId"
    }

    $writeScopeProperty = $RoleObject.PSObject.Properties["writeScope"]
    if (
        $null -eq $writeScopeProperty -or
        $writeScopeProperty.Value -isnot [System.Array]
    ) {
        Add-PreflightError -Code "$ErrorPrefix-WRITE-SCOPE-INVALID-$roleId"
        return
    }
    $actualWriteScope = [string[]]@($writeScopeProperty.Value)
    $expectedWriteScope = [string[]]@($ExpectedSpec.writeScope)
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual $actualWriteScope `
        -Expected $expectedWriteScope)) {
        Add-PreflightError -Code "$ErrorPrefix-WRITE-SCOPE-SET-DRIFT-$roleId"
    }
    $writeScopeSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($scopeValue in $actualWriteScope) {
        if (-not $writeScopeSet.Add($scopeValue)) {
            Add-PreflightError -Code "$ErrorPrefix-WRITE-SCOPE-DUPLICATE-$roleId"
        }
        if (-not (Test-CanonicalRoleWriteScope -Value $scopeValue)) {
            Add-PreflightError -Code "$ErrorPrefix-WRITE-SCOPE-SYNTAX-$roleId"
        }
        elseif (Test-ProductionRoleWriteScope `
            -Value $scopeValue `
            -Category $ExpectedSpec.category) {
            Add-PreflightError -Code "$ErrorPrefix-WRITE-SCOPE-PRODUCTION-$roleId"
        }
    }
}

function Assert-RoleCatalog {
    param(
        [AllowNull()][object]$RolesValue,
        [Parameter(Mandatory)][object[]]$ExpectedSpecs,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    if ($RolesValue -isnot [System.Array]) {
        Add-PreflightError -Code "$ErrorPrefix-TABLE-NOT-ARRAY"
        return
    }
    $roleObjects = @($RolesValue)
    $expectedIds = [string[]]@(
        $ExpectedSpecs | ForEach-Object { $_.id }
    )
    $actualIds = [string[]]@(
        $roleObjects | ForEach-Object {
            [string](Get-ContractProperty $_ "id")
        }
    )
    if (
        $actualIds.Count -ne $expectedIds.Count -or
        -not (Test-OrdinalStringArrayEqual `
            -Actual $actualIds `
            -Expected $expectedIds)
    ) {
        Add-PreflightError -Code "$ErrorPrefix-TABLE-INVALID"
    }
    $roleIdSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($roleId in $actualIds) {
        if (-not $roleIdSet.Add($roleId)) {
            Add-PreflightError -Code "$ErrorPrefix-DUPLICATE-ID-$roleId"
        }
    }
    for (
        $roleIndex = 0;
        $roleIndex -lt $ExpectedSpecs.Count;
        $roleIndex++
    ) {
        $roleObject = if ($roleIndex -lt $roleObjects.Count) {
            $roleObjects[$roleIndex]
        }
        else {
            $null
        }
        Assert-RoleDefinition `
            -RoleObject $roleObject `
            -ExpectedSpec $ExpectedSpecs[$roleIndex] `
            -ErrorPrefix $ErrorPrefix
    }
}

function Test-OrdinalSetEqual {
    param(
        [Parameter(Mandatory)][string[]]$Actual,
        [Parameter(Mandatory)][string[]]$Expected
    )

    $actualSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $expectedSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )

    foreach ($item in $Actual) {
        if ([string]::IsNullOrWhiteSpace($item)) {
            return $false
        }
        [void]$actualSet.Add($item)
    }
    foreach ($item in $Expected) {
        [void]$expectedSet.Add($item)
    }

    return $actualSet.SetEquals($expectedSet)
}

function Test-OrdinalStringArrayEqual {
    param(
        [AllowNull()][object]$Actual,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Expected
    )

    if ($Actual -isnot [System.Array]) {
        return $false
    }

    $actualItems = @($Actual)
    if ($actualItems.Count -ne $Expected.Count) {
        return $false
    }

    for ($itemIndex = 0; $itemIndex -lt $Expected.Count; $itemIndex++) {
        if (
            $actualItems[$itemIndex] -isnot [string] -or
            $actualItems[$itemIndex] -cne $Expected[$itemIndex]
        ) {
            return $false
        }
    }

    return $true
}

function Test-ExactObjectProperties {
    param(
        [AllowNull()][object]$ObjectValue,
        [Parameter(Mandatory)][string[]]$ExpectedProperties
    )

    if ($null -eq $ObjectValue) {
        return $false
    }

    $actualProperties = @(
        $ObjectValue.PSObject.Properties | ForEach-Object { $_.Name }
    )
    if ($actualProperties.Count -ne $ExpectedProperties.Count) {
        return $false
    }

    return Test-OrdinalSetEqual `
        -Actual ([string[]]$actualProperties) `
        -Expected $ExpectedProperties
}

function Test-RequiredTextAnchorPointers {
    param([AllowNull()][object]$Value)

    if ($Value -isnot [System.Array]) {
        return $false
    }
    $actualPointers = @($Value)
    if ($actualPointers.Count -ne $expectedRequiredTextAnchorPointers.Count) {
        return $false
    }
    for (
        $anchorIndex = 0;
        $anchorIndex -lt $expectedRequiredTextAnchorPointers.Count;
        $anchorIndex++
    ) {
        $actualAnchor = $actualPointers[$anchorIndex]
        $expectedAnchor = $expectedRequiredTextAnchorPointers[$anchorIndex]
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $actualAnchor `
            -ExpectedProperties @("id", "path"))) {
            return $false
        }
        if (
            (Get-ContractProperty $actualAnchor "id") -cne
            $expectedAnchor.id -or
            (Get-ContractProperty $actualAnchor "path") -cne
            $expectedAnchor.path
        ) {
            return $false
        }
    }
    return $true
}

function Get-Utf8LfSha256 {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$TextValue
    )

    $normalized = $TextValue.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [System.StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($normalized)
    return [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($bytes)
    ).ToLowerInvariant()
}

function Get-BytesSha256 {
    param(
        [Parameter(Mandatory)][byte[]]$Bytes
    )

    return [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($Bytes)
    ).ToLowerInvariant()
}

function Get-CompactJsonSha256 {
    param(
        [Parameter(Mandatory)][object]$Value
    )

    $json = $Value | ConvertTo-Json -Compress -Depth 20
    return Get-Utf8LfSha256 -TextValue $json
}

function Write-CanonicalJsonElement {
    param(
        [Parameter(Mandatory)][System.Text.Json.Utf8JsonWriter]$Writer,
        [Parameter(Mandatory)][System.Text.Json.JsonElement]$Element
    )

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $Writer.WriteStartObject()
        $propertyNames = [string[]]@(
            $Element.EnumerateObject() | ForEach-Object { $_.Name }
        )
        [Array]::Sort($propertyNames, [System.StringComparer]::Ordinal)
        foreach ($propertyName in $propertyNames) {
            $Writer.WritePropertyName($propertyName)
            Write-CanonicalJsonElement `
                -Writer $Writer `
                -Element $Element.GetProperty($propertyName)
        }
        $Writer.WriteEndObject()
        return
    }

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        $Writer.WriteStartArray()
        foreach ($item in $Element.EnumerateArray()) {
            Write-CanonicalJsonElement -Writer $Writer -Element $item
        }
        $Writer.WriteEndArray()
        return
    }

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::String) {
        $Writer.WriteStringValue($Element.GetString())
        return
    }

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Number) {
        $Writer.WriteRawValue($Element.GetRawText(), $true)
        return
    }

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::True) {
        $Writer.WriteBooleanValue($true)
        return
    }

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::False) {
        $Writer.WriteBooleanValue($false)
        return
    }

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Null) {
        $Writer.WriteNullValue()
        return
    }

    throw "Unsupported canonical JSON value kind: $($Element.ValueKind)"
}

function Get-CanonicalJsonText {
    param(
        [Parameter(Mandatory)][AllowNull()][object]$Value
    )

    $serialized = ConvertTo-Json -InputObject $Value -Compress -Depth 100
    $document = [System.Text.Json.JsonDocument]::Parse($serialized)
    $memory = [System.IO.MemoryStream]::new()
    $writerOptions = [System.Text.Json.JsonWriterOptions]::new()
    $writerOptions.Indented = $false
    $writer = [System.Text.Json.Utf8JsonWriter]::new($memory, $writerOptions)
    try {
        Write-CanonicalJsonElement -Writer $writer -Element $document.RootElement
        $writer.Flush()
        return $utf8Output.GetString($memory.ToArray())
    }
    finally {
        $writer.Dispose()
        $document.Dispose()
        $memory.Dispose()
    }
}

function Get-ImmutableContractProjection {
    param(
        [Parameter(Mandatory)][object]$Contract,
        [Parameter(Mandatory)][string[]]$Fields
    )

    $sortedFields = [string[]]@($Fields.Clone())
    [Array]::Sort($sortedFields, [System.StringComparer]::Ordinal)
    $projection = [ordered]@{}
    foreach ($fieldName in $sortedFields) {
        $property = $Contract.PSObject.Properties[$fieldName]
        if ($null -eq $property) {
            throw "Immutable contract field is missing: $fieldName"
        }
        $projection[$fieldName] = $property.Value
    }
    return [pscustomobject]$projection
}

function Get-JsonPointerToken {
    param([Parameter(Mandatory)][string]$Name)
    return $Name.Replace("~", "~0").Replace("/", "~1")
}

function Test-JsonObjectValue {
    param([AllowNull()][object]$Value)
    return $Value -is [System.Management.Automation.PSCustomObject]
}

function Add-JsonMutationDiff {
    param(
        [AllowNull()][object]$SourceValue,
        [AllowNull()][object]$CurrentValue,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Pointer,
        [Parameter(Mandatory)][AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Mutations
    )

    if ($null -eq $SourceValue -and $null -eq $CurrentValue) {
        return
    }
    if ($null -eq $SourceValue -or $null -eq $CurrentValue) {
        $Mutations.Add($Pointer)
        return
    }

    $sourceIsObject = Test-JsonObjectValue -Value $SourceValue
    $currentIsObject = Test-JsonObjectValue -Value $CurrentValue
    if ($sourceIsObject -or $currentIsObject) {
        if (-not $sourceIsObject -or -not $currentIsObject) {
            $Mutations.Add($Pointer)
            return
        }

        $names = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        foreach ($property in $SourceValue.PSObject.Properties) {
            [void]$names.Add($property.Name)
        }
        foreach ($property in $CurrentValue.PSObject.Properties) {
            [void]$names.Add($property.Name)
        }
        $sortedNames = [string[]]@($names)
        [Array]::Sort($sortedNames, [System.StringComparer]::Ordinal)
        foreach ($name in $sortedNames) {
            $sourceProperty = $SourceValue.PSObject.Properties[$name]
            $currentProperty = $CurrentValue.PSObject.Properties[$name]
            $childPointer = $Pointer + "/" + (Get-JsonPointerToken -Name $name)
            if ($null -eq $sourceProperty -or $null -eq $currentProperty) {
                $Mutations.Add($childPointer)
                continue
            }
            Add-JsonMutationDiff `
                -SourceValue $sourceProperty.Value `
                -CurrentValue $currentProperty.Value `
                -Pointer $childPointer `
                -Mutations $Mutations
        }
        return
    }

    $sourceIsArray = $SourceValue -is [System.Array]
    $currentIsArray = $CurrentValue -is [System.Array]
    if ($sourceIsArray -or $currentIsArray) {
        if (-not $sourceIsArray -or -not $currentIsArray) {
            $Mutations.Add($Pointer)
            return
        }

        $sourceItems = @($SourceValue)
        $currentItems = @($CurrentValue)
        if ($sourceItems.Count -ne $currentItems.Count) {
            $Mutations.Add($Pointer)
            return
        }
        for ($itemIndex = 0; $itemIndex -lt $sourceItems.Count; $itemIndex++) {
            Add-JsonMutationDiff `
                -SourceValue $sourceItems[$itemIndex] `
                -CurrentValue $currentItems[$itemIndex] `
                -Pointer ($Pointer + "/" + $itemIndex) `
                -Mutations $Mutations
        }
        return
    }

    $sourceJson = Get-CanonicalJsonText -Value $SourceValue
    $currentJson = Get-CanonicalJsonText -Value $CurrentValue
    if ($sourceJson -cne $currentJson) {
        $Mutations.Add($Pointer)
    }
}

function Test-JsonPointerAllowed {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Pointer,
        [Parameter(Mandatory)][string[]]$AllowedPatterns
    )

    foreach ($pattern in $AllowedPatterns) {
        $regexPattern = (
            "^" +
            ([regex]::Escape($pattern).Replace("\*", "[^/]+")) +
            "$"
        )
        if ([regex]::IsMatch(
            $Pointer,
            $regexPattern,
            [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
        )) {
            return $true
        }
    }
    return $false
}

function Get-ChangedRepoPaths {
    param(
        [Parameter(Mandatory)][string]$RepositoryRootPath,
        [Parameter(Mandatory)][string]$BaseRevision,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    $changed = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $diffPaths = @(
        & git -C $RepositoryRootPath diff --name-only --no-renames $BaseRevision -- 2>$null
    )
    if ($LASTEXITCODE -ne 0) {
        Add-PreflightError -Code "$ErrorPrefix-GIT-DIFF-FAILED"
        return [string[]]@()
    }
    foreach ($pathValue in $diffPaths) {
        $normalized = ([string]$pathValue).Replace("\", "/")
        if (-not [string]::IsNullOrWhiteSpace($normalized)) {
            [void]$changed.Add($normalized)
        }
    }

    $untrackedPaths = @(
        & git -C $RepositoryRootPath ls-files --others --exclude-standard 2>$null
    )
    if ($LASTEXITCODE -ne 0) {
        Add-PreflightError -Code "$ErrorPrefix-GIT-UNTRACKED-FAILED"
        return [string[]]@()
    }
    foreach ($pathValue in $untrackedPaths) {
        $normalized = ([string]$pathValue).Replace("\", "/")
        if (-not [string]::IsNullOrWhiteSpace($normalized)) {
            [void]$changed.Add($normalized)
        }
    }

    $paths = [string[]]@($changed)
    [Array]::Sort($paths, [System.StringComparer]::Ordinal)
    return $paths
}

function Test-ChangedPathAllowed {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$ExactPaths,
        [Parameter(Mandatory)][string[]]$AllowedPrefixes
    )

    if ($Path -cin $ExactPaths) {
        return $true
    }
    foreach ($prefix in $AllowedPrefixes) {
        if ($Path.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

function Get-DeviceLockSnapshot {
    param(
        [Parameter(Mandatory)][string]$DeviceLockPath
    )

    if (-not (Test-Path -LiteralPath $DeviceLockPath -PathType Leaf)) {
        Add-PreflightError -Code "DEVICE-LOCK-MISSING"
        return $null
    }

    $bytes = [System.IO.File]::ReadAllBytes($DeviceLockPath)
    try {
        $text = $utf8Strict.GetString($bytes)
    }
    catch {
        Add-PreflightError -Code "DEVICE-LOCK-NOT-UTF8"
        return $null
    }
    $lines = @($text.Replace("`r", "").Split("`n"))
    $currentStart = -1
    $currentEnd = $lines.Count
    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex] -ceq "## 当前占用") {
            $currentStart = $lineIndex + 1
            break
        }
    }
    if ($currentStart -lt 0) {
        Add-PreflightError -Code "DEVICE-LOCK-CURRENT-SECTION-MISSING"
        return $null
    }
    for ($lineIndex = $currentStart; $lineIndex -lt $lines.Count; $lineIndex++) {
        if ($lines[$lineIndex].StartsWith("## ", [System.StringComparison]::Ordinal)) {
            $currentEnd = $lineIndex
            break
        }
    }
    $activeCount = 0
    for ($lineIndex = $currentStart; $lineIndex -lt $currentEnd; $lineIndex++) {
        if ($lines[$lineIndex] -match '^### .*— ACTIVE(?:\s|$)') {
            $activeCount++
        }
    }
    $state = if ($activeCount -eq 0) {
        "RELEASED_ACTIVE_0"
    }
    else {
        "ACTIVE_COUNT_$activeCount"
    }

    return [pscustomobject][ordered]@{
        sha256 = Get-BytesSha256 -Bytes $bytes
        state = $state
        activeCount = $activeCount
    }
}

function Get-QaToolsDigestSnapshot {
    param(
        [Parameter(Mandatory)][string]$ToolsRoot
    )

    $rootPath = (Resolve-Path -LiteralPath $ToolsRoot).Path
    $files = @(
        Get-ChildItem -LiteralPath $rootPath -File -Recurse -Force |
            Where-Object { $_.Name -ne 'tools.sha256' }
    )
    $byRelativePath = @{}
    foreach ($file in $files) {
        $relativePath = [System.IO.Path]::GetRelativePath(
            $rootPath,
            $file.FullName
        ).Replace('\', '/')
        $byRelativePath[$relativePath] = $file
    }

    $relativePaths = [string[]]@($byRelativePath.Keys)
    [Array]::Sort($relativePaths, [System.StringComparer]::Ordinal)
    $manifestLines = @(
        foreach ($relativePath in $relativePaths) {
            $fileSha256 = (
                Get-FileHash `
                    -LiteralPath $byRelativePath[$relativePath].FullName `
                    -Algorithm SHA256
            ).Hash.ToLowerInvariant()
            "$fileSha256  tools/$relativePath"
        }
    )
    $manifestText = if ($manifestLines.Count -gt 0) {
        ($manifestLines -join "`n") + "`n"
    }
    else {
        ''
    }
    $manifestBytes = [System.Text.UTF8Encoding]::new($false).GetBytes(
        $manifestText
    )
    $digestSha256 = [Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($manifestBytes)
    ).ToLowerInvariant()

    return [pscustomobject][ordered]@{
        digestSha256 = $digestSha256
        entryCount = $relativePaths.Count
        relativePaths = $relativePaths
        manifestLines = $manifestLines
        manifestText = $manifestText
    }
}

function Read-StrictUtf8LfFile {
    param(
        [Parameter(Mandatory)][string]$AbsolutePath,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    if (-not (Test-Path -LiteralPath $AbsolutePath -PathType Leaf)) {
        Add-PreflightError -Code "$ErrorPrefix-MISSING"
        return $null
    }

    $rawBytes = [System.IO.File]::ReadAllBytes($AbsolutePath)
    if (
        $rawBytes.Length -ge 3 -and
        $rawBytes[0] -eq 0xEF -and
        $rawBytes[1] -eq 0xBB -and
        $rawBytes[2] -eq 0xBF
    ) {
        Add-PreflightError -Code "$ErrorPrefix-BOM"
    }
    if ([Array]::IndexOf($rawBytes, [byte]13) -ge 0) {
        Add-PreflightError -Code "$ErrorPrefix-CRLF"
    }

    try {
        return $utf8Strict.GetString($rawBytes)
    }
    catch {
        Add-PreflightError -Code "$ErrorPrefix-NOT-UTF8"
        return $null
    }
}

function Resolve-RepoFile {
    param(
        [Parameter(Mandatory)][string]$RelativeOrAbsolutePath,
        [Parameter(Mandatory)][string]$RepositoryRootPath,
        [Parameter(Mandatory)][string]$ErrorCode
    )

    if ([string]::IsNullOrWhiteSpace($RelativeOrAbsolutePath)) {
        Add-PreflightError -Code "$ErrorCode-EMPTY"
        return $null
    }

    try {
        if ([System.IO.Path]::IsPathRooted($RelativeOrAbsolutePath)) {
            $fullPath = [System.IO.Path]::GetFullPath($RelativeOrAbsolutePath)
        }
        else {
            $fullPath = [System.IO.Path]::GetFullPath(
                (Join-Path $RepositoryRootPath $RelativeOrAbsolutePath)
            )
        }
    }
    catch {
        Add-PreflightError -Code "$ErrorCode-INVALID"
        return $null
    }

    $repositoryPrefix = $RepositoryRootPath.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar

    if (
        -not $fullPath.Equals($RepositoryRootPath, [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $fullPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    ) {
        Add-PreflightError -Code "$ErrorCode-OUTSIDE-REPO"
        return $null
    }

    return $fullPath
}

function Get-JsonPointerValue {
    param(
        [AllowNull()][object]$RootValue,
        [Parameter(Mandatory)][string]$JsonPointer
    )

    if (-not $JsonPointer.StartsWith("#/", [System.StringComparison]::Ordinal)) {
        return $null
    }

    $currentValue = $RootValue
    foreach ($rawSegment in $JsonPointer.Substring(2).Split("/")) {
        $segment = $rawSegment.Replace("~1", "/").Replace("~0", "~")
        if ($null -eq $currentValue) {
            return $null
        }
        $propertyInfo = $currentValue.PSObject.Properties[$segment]
        if ($null -eq $propertyInfo) {
            return $null
        }
        $currentValue = $propertyInfo.Value
    }

    return $currentValue
}

function Get-PromptText {
    param(
        [AllowNull()][object]$PromptValue,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    $lines = Get-ContractProperty -ObjectValue $PromptValue -PropertyName "textLines"
    if (-not (Test-StringArray -Value $lines)) {
        Add-PreflightError -Code "$ErrorPrefix-TEXT-LINES"
        return $null
    }

    return ($lines -join "`n")
}

function Assert-Tokens {
    param(
        [AllowNull()][string]$TextValue,
        [Parameter(Mandatory)][string[]]$Tokens,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    if ($Tokens.Count -eq 0) {
        Add-PreflightError -Code "$ErrorPrefix-TOKENS-EMPTY"
        return
    }
    $validTokens = [System.Collections.Generic.List[string]]::new()
    foreach ($token in $Tokens) {
        if (
            $token -isnot [string] -or
            [string]::IsNullOrWhiteSpace($token) -or
            $token -cne $token.Trim() -or
            $token.Trim().Length -lt 2 -or
            $token.Contains("*", [System.StringComparison]::Ordinal) -or
            $token.Contains("?", [System.StringComparison]::Ordinal) -or
            $token.Contains("`r", [System.StringComparison]::Ordinal) -or
            $token.Contains("`n", [System.StringComparison]::Ordinal)
        ) {
            Add-PreflightError -Code "$ErrorPrefix-TOKEN-INVALID"
            continue
        }
        $validTokens.Add($token)
    }
    if ($null -eq $TextValue) {
        Add-PreflightError -Code "$ErrorPrefix-TEXT-MISSING"
        return
    }

    foreach ($token in $validTokens) {
        if (-not $TextValue.Contains($token, [System.StringComparison]::Ordinal)) {
            $tokenCode = $token.Replace("/", "~").Replace(" ", "_")
            Add-PreflightError -Code "$ErrorPrefix-TOKEN-MISSING-$tokenCode"
        }
    }
}

function Read-GitCommitPathStrictUtf8LfFile {
    param(
        [Parameter(Mandatory)][string]$RepositoryRootPath,
        [Parameter(Mandatory)][string]$CommitId,
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$ErrorPrefix
    )

    if ($CommitId -notmatch "^[0-9a-f]{40}$") {
        Add-PreflightError -Code "$ErrorPrefix-COMMIT-INVALID"
        return $null
    }
    if (
        [string]::IsNullOrWhiteSpace($RelativePath) -or
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath.Contains("\", [System.StringComparison]::Ordinal) -or
        $RelativePath.Contains(":", [System.StringComparison]::Ordinal) -or
        $RelativePath.Contains("`r", [System.StringComparison]::Ordinal) -or
        $RelativePath.Contains("`n", [System.StringComparison]::Ordinal) -or
        $RelativePath.StartsWith("/", [System.StringComparison]::Ordinal) -or
        $RelativePath.EndsWith("/", [System.StringComparison]::Ordinal) -or
        @($RelativePath.Split("/") | Where-Object { $_ -ceq ".." }).Count -gt 0
    ) {
        Add-PreflightError -Code "$ErrorPrefix-PATH-INVALID"
        return $null
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = "git"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in @(
        "-C",
        $RepositoryRootPath,
        "cat-file",
        "blob",
        "${CommitId}:${RelativePath}"
    )) {
        [void]$process.StartInfo.ArgumentList.Add($argument)
    }

    $memory = [System.IO.MemoryStream]::new()
    try {
        if (-not $process.Start()) {
            Add-PreflightError -Code "$ErrorPrefix-START-FAILED"
            return $null
        }
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardOutput.BaseStream.CopyTo($memory)
        $stderrText = $stderrTask.GetAwaiter().GetResult()
        $process.WaitForExit()
        $bytes = $memory.ToArray()
        if ($process.ExitCode -ne 0) {
            Add-PreflightError -Code "$ErrorPrefix-READ-FAILED"
            return $null
        }
        if ($stderrText.Length -gt 0) {
            Add-PreflightError -Code "$ErrorPrefix-STDERR-NOT-EMPTY"
            return $null
        }
        if ($bytes.Length -eq 0) {
            Add-PreflightError -Code "$ErrorPrefix-EMPTY"
            return $null
        }
        if (
            $bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF
        ) {
            Add-PreflightError -Code "$ErrorPrefix-BOM-FORBIDDEN"
            return $null
        }
        if (
            [Array]::IndexOf($bytes, [byte]13) -ge 0 -or
            $bytes[$bytes.Length - 1] -ne [byte]10
        ) {
            Add-PreflightError -Code "$ErrorPrefix-LF-REQUIRED"
            return $null
        }
        try {
            return $utf8Strict.GetString($bytes)
        }
        catch {
            Add-PreflightError -Code "$ErrorPrefix-UTF8-INVALID"
            return $null
        }
    }
    finally {
        $process.Dispose()
        $memory.Dispose()
    }
}

function Assert-PreAuditState {
    param(
        [AllowNull()][object]$Contract,
        [Parameter(Mandatory)]
        [ValidateSet("SOURCE", "CURRENT")]
        [string]$Mode,
        [Parameter(Mandatory)][object]$ReferenceContract,
        [Parameter(Mandatory)][string[]]$RequiredTopLevelNames,
        [Parameter(Mandatory)][string[]]$ExpectedRoleIds,
        [Parameter(Mandatory)][string[]]$ExpectedImmutableStateFields,
        [Parameter(Mandatory)][string[]]$ExpectedActiveTokenPointers,
        [Parameter(Mandatory)][object[]]$ExpectedRequiredTextAnchorPointers,
        [Parameter(Mandatory)][string[]]$ExpectedPostMutationPointerAllowlist,
        [Parameter(Mandatory)][string[]]$ExpectedPostAuditTransitionPaths,
        [Parameter(Mandatory)][string[]]$ExpectedAuditReceiptSchema,
        [Parameter(Mandatory)][string]$SourceContractRelativePath
    )

    $isSourceMode = $Mode -ceq "SOURCE"
    $errorPrefix = if ($isSourceMode) {
        "AUDIT-SOURCE-PRE"
    }
    else {
        "CURRENT-PRE"
    }
    if (-not (Test-JsonObjectValue -Value $Contract)) {
        Add-PreflightError -Code "$errorPrefix-CONTRACT-NOT-OBJECT"
        return
    }

    $actualTopLevelNames = [string[]]@(
        $Contract.PSObject.Properties | ForEach-Object { $_.Name }
    )
    if (
        $actualTopLevelNames.Count -ne $RequiredTopLevelNames.Count -or
        -not (Test-OrdinalSetEqual `
            -Actual $actualTopLevelNames `
            -Expected $RequiredTopLevelNames)
    ) {
        Add-PreflightError -Code "$errorPrefix-TOP-LEVEL-SET-INVALID"
    }
    if (
        (Get-ContractProperty $Contract "contractVersion") -cne
        "HANDOFF_BOOTSTRAP_V2" -or
        (Get-ContractProperty $Contract "schema") -cne
        "aurorama.handoff-bootstrap.v2"
    ) {
        Add-PreflightError -Code "$errorPrefix-IDENTITY-INVALID"
    }

    $sourceValidation = Get-ContractProperty $Contract "validation"
    if (
        (Get-ContractProperty $sourceValidation "handoffPhase") -cne
        "PRE_HANDOFF_AUDIT" -or
        $null -ne (Get-ContractProperty $Contract "auditReceipt")
    ) {
        Add-PreflightError -Code "$errorPrefix-PHASE-OR-RECEIPT-INVALID"
    }
    $expectedValidationProperties = @(
        "handoffPhase",
        "activeDocument",
        "activeHeadingPrefix",
        "activePromptExpectedRoleId",
        "qaPromptRequiredStatus",
        "tasksDocument",
        "tasksInProgressHeading",
        "tasksInProgressExpectedCount",
        "memoryIndexPath",
        "preAuditAllowedDirtyPaths",
        "postAuditTransitionPaths",
        "auditReceiptSchema",
        "immutableStateFields",
        "postMutationPointerAllowlist",
        "qaPromptSha256Algorithm",
        "rejectedScopeHashAlgorithm",
        "memoryIndexSha256Algorithm",
        "deviceLockStateAlgorithm",
        "toolsDigestAlgorithm",
        "immutableStateSha256Algorithm",
        "activeRequiredTokens",
        "requiredTextAnchors"
    )
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $sourceValidation `
        -ExpectedProperties $expectedValidationProperties)) {
        Add-PreflightError -Code "$errorPrefix-VALIDATION-SCHEMA-INVALID"
    }
    if ($isSourceMode) {
        foreach ($validationName in $expectedValidationProperties) {
            if ($validationName -cin @(
                "handoffPhase",
                "activePromptExpectedRoleId",
                "qaPromptRequiredStatus"
            )) {
                continue
            }
            $sourceValue = Get-ContractProperty $sourceValidation $validationName
            $referenceValue = Get-ContractProperty `
                (Get-ContractProperty $ReferenceContract "validation") `
                $validationName
            if (
                (Get-CanonicalJsonText -Value $sourceValue) -cne
                (Get-CanonicalJsonText -Value $referenceValue)
            ) {
                Add-PreflightError -Code "$errorPrefix-VALIDATION-DRIFT-$validationName"
            }
        }
    }
    $contractReviewState = [string](
        Get-ContractProperty (
            Get-ContractProperty $Contract "qaRoundState"
        ) "independentIntegrationReview"
    )
    $expectedActivePromptRoleIdByReviewState = if (
        $contractReviewState -ceq "PASS"
    ) {
            "PRE_HANDOFF_AUDIT_OWNER"
    }
    else {
            "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
    }
    if (
        (Get-ContractProperty $sourceValidation "activePromptExpectedRoleId") -cne
        $expectedActivePromptRoleIdByReviewState -or
        (Get-ContractProperty $sourceValidation "qaPromptRequiredStatus") -cne
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    ) {
        Add-PreflightError -Code "$errorPrefix-VALIDATION-PHASE-BINDING-INVALID"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual ([string[]]@(
            Get-ContractProperty $sourceValidation "activeRequiredTokens"
        )) `
        -Expected $ExpectedActiveTokenPointers)) {
        Add-PreflightError -Code "$errorPrefix-ACTIVE-TOKEN-POINTER-DRIFT"
    }
    $sourceAnchorPointers = @(
        Get-ContractProperty $sourceValidation "requiredTextAnchors"
    )
    if (
        (Get-CanonicalJsonText -Value $sourceAnchorPointers) -cne
        (Get-CanonicalJsonText -Value $ExpectedRequiredTextAnchorPointers)
    ) {
        Add-PreflightError -Code "$errorPrefix-ANCHOR-POINTER-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $sourceValidation "immutableStateFields") `
        -Expected $ExpectedImmutableStateFields)) {
        Add-PreflightError -Code "$errorPrefix-IMMUTABLE-FIELD-LIST-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $sourceValidation "postMutationPointerAllowlist") `
        -Expected $ExpectedPostMutationPointerAllowlist)) {
        Add-PreflightError -Code "$errorPrefix-MUTATION-ALLOWLIST-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $sourceValidation "postAuditTransitionPaths") `
        -Expected $ExpectedPostAuditTransitionPaths)) {
        Add-PreflightError -Code "$errorPrefix-TRANSITION-ALLOWLIST-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $sourceValidation "auditReceiptSchema") `
        -Expected $ExpectedAuditReceiptSchema)) {
        Add-PreflightError -Code "$errorPrefix-RECEIPT-SCHEMA-DRIFT"
    }

    $sourceImmutableProjection = Get-ImmutableContractProjection `
        -Contract $Contract `
        -Fields $ExpectedImmutableStateFields
    $referenceImmutableProjection = Get-ImmutableContractProjection `
        -Contract $ReferenceContract `
        -Fields $ExpectedImmutableStateFields
    if (
        (Get-CanonicalJsonText -Value $sourceImmutableProjection) -cne
        (Get-CanonicalJsonText -Value $referenceImmutableProjection)
    ) {
        Add-PreflightError -Code "$errorPrefix-IMMUTABLE-STATE-DRIFT"
    }

    if (-not (Test-OrdinalStringArrayEqual `
        -Actual $ExpectedRoleIds `
        -Expected $expectedRoleIds)) {
        Add-PreflightError -Code "$errorPrefix-ROLE-CONSTANT-DRIFT"
    }
    Assert-RoleCatalog `
        -RolesValue (Get-ContractProperty $Contract "roles") `
        -ExpectedSpecs $immutableRoleSpecs `
        -ErrorPrefix $errorPrefix-ROLE

    $sourceQaRoundState = Get-ContractProperty $Contract "qaRoundState"
    $qaRoundStateProperties = @(
        "status",
        "lastQaVerdict",
        "round1IntegrationReview",
        "round2IntegrationReview",
        "independentIntegrationReview",
        "preHandoffAudit",
        "padQaPromptDispatch",
        "productFindings",
        "coreStepsExecuted",
        "padTenMinuteCoreRun",
        "k60Registration",
        "k60CoreRun",
        "cleanup",
        "padLockActiveCount"
    )
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $sourceQaRoundState `
        -ExpectedProperties $qaRoundStateProperties)) {
        Add-PreflightError -Code "$errorPrefix-QA-ROUND-STATE-SCHEMA-INVALID"
    }
    $reviewState = [string](
        Get-ContractProperty $sourceQaRoundState "independentIntegrationReview"
    )
    $preAuditState = if ($reviewState -ceq "BLOCKED_PENDING_ROUND9_REVIEW") {
        "PRE_REVIEW_PENDING"
    }
    elseif ($reviewState -ceq "PASS") {
        "PRE_REVIEW_PASS_AWAITING_AUDIT"
    }
    else {
        "INVALID"
    }
    $expectedSourceRoundStatus = switch ($preAuditState) {
        "PRE_REVIEW_PENDING" {
            "ROUND8_PASS_PRE_STATE_REPAIRED_AWAITING_FRESH_ROUND9_REVIEW"
        }
        "PRE_REVIEW_PASS_AWAITING_AUDIT" {
            "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT"
        }
        default {
            "INVALID"
        }
    }
    $sourceRoundExpectations = [ordered]@{
        status = $expectedSourceRoundStatus
        lastQaVerdict = "BLOCKED_TOOL_NONCONVERGENCE"
        round1IntegrationReview = "FAIL_1_P1_2_P2_REPAIRED_CLOSED_BY_ROUND2"
        round2IntegrationReview = "FAIL_P2_PREFLIGHT_SEMANTIC_BYPASS_REPAIRED_CLOSED_BY_ROUND3_PASS"
        preHandoffAudit = "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
        padQaPromptDispatch = "BLOCKED"
        coreStepsExecuted = 0
        padTenMinuteCoreRun = "NOT_RUN"
        k60Registration = "NOT_REGISTERED"
        k60CoreRun = "NOT_RUN"
        cleanup = "PASS_CLEANUP_AND_RESTORE"
        padLockActiveCount = 0
    }
    if ($expectedSourceRoundStatus -ceq "INVALID") {
        Add-PreflightError -Code "$errorPrefix-REVIEW-STATE-INVALID"
    }
    foreach ($stateName in $sourceRoundExpectations.Keys) {
        if (
            (Get-ContractProperty $sourceQaRoundState $stateName) -cne
            $sourceRoundExpectations[$stateName]
        ) {
            Add-PreflightError -Code "$errorPrefix-QA-ROUND-STATE-INVALID-$stateName"
        }
    }
    $sourceProductFindings = Get-ContractProperty `
        $sourceQaRoundState `
        "productFindings"
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $sourceProductFindings `
        -ExpectedProperties @("P0", "P1", "P2", "P3"))) {
        Add-PreflightError -Code "$errorPrefix-PRODUCT-FINDINGS-SCHEMA-INVALID"
    }
    foreach ($severity in @("P0", "P1", "P2", "P3")) {
        if (
            (Get-ContractProperty $sourceProductFindings $severity) -cne
            "NOT_ASSESSED"
        ) {
            Add-PreflightError -Code "$errorPrefix-PRODUCT-INVALID-$severity"
        }
    }

    $sourceGateContainer = Get-ContractProperty $Contract "requiredGates"
    $expectedGateSpecs = @(
        [pscustomobject]@{
            id = "PREFLIGHT_PASS"
            owner = "ROOT_INTEGRATION"
            requiredBefore = [string[]]@()
        },
        [pscustomobject]@{
            id = "TOOL_NONCONVERGENCE_REPAIRED"
            owner = "TOOL_REPAIR_OWNER"
            requiredBefore = [string[]]@("PREFLIGHT_PASS")
        },
        [pscustomobject]@{
            id = "CONTRACT_DIGEST_REFRESHED"
            owner = "DOC_PROTOCOL_OWNER"
            requiredBefore = [string[]]@("TOOL_NONCONVERGENCE_REPAIRED")
        },
        [pscustomobject]@{
            id = "INDEPENDENT_INTEGRATION_REVIEW_PASS"
            owner = "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
            requiredBefore = [string[]]@("CONTRACT_DIGEST_REFRESHED")
        },
        [pscustomobject]@{
            id = "PRE_HANDOFF_AUDIT_PASS"
            owner = "PRE_HANDOFF_AUDIT_OWNER"
            requiredBefore = [string[]]@(
                "INDEPENDENT_INTEGRATION_REVIEW_PASS"
            )
        },
        [pscustomobject]@{
            id = "QA_DISPATCHABLE"
            owner = "PAD_QA_OWNER"
            requiredBefore = [string[]]@("PRE_HANDOFF_AUDIT_PASS")
        }
    )
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $sourceGateContainer `
        -ExpectedProperties @("lifecycleVersion", "lifecycle"))) {
        Add-PreflightError -Code "$errorPrefix-GATE-CONTAINER-INVALID"
    }
    $sourceGates = @(Get-ContractProperty $sourceGateContainer "lifecycle")
    if (
        (Get-ContractProperty $sourceGateContainer "lifecycleVersion") -cne
        "AURORAMA_HANDOFF_GATE_ORDER_V2"
    ) {
        Add-PreflightError -Code "$errorPrefix-GATE-LIFECYCLE-VERSION-INVALID"
    }
    if ($sourceGates.Count -ne $expectedGateSpecs.Count) {
        Add-PreflightError -Code "$errorPrefix-GATE-COUNT-INVALID"
    }
    for ($gateIndex = 0; $gateIndex -lt $expectedGateSpecs.Count; $gateIndex++) {
        $gate = if ($gateIndex -lt $sourceGates.Count) {
            $sourceGates[$gateIndex]
        }
        else {
            $null
        }
        $expectedGate = $expectedGateSpecs[$gateIndex]
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $gate `
            -ExpectedProperties @("id", "owner", "requiredBefore", "status"))) {
            Add-PreflightError -Code "$errorPrefix-GATE-SCHEMA-INVALID-$gateIndex"
            continue
        }
        if (
            (Get-ContractProperty $gate "id") -cne $expectedGate.id -or
            (Get-ContractProperty $gate "owner") -cne $expectedGate.owner -or
            -not (Test-OrdinalStringArrayEqual `
                -Actual ([string[]]@(
                    Get-ContractProperty $gate "requiredBefore"
                )) `
                -Expected $expectedGate.requiredBefore)
        ) {
            Add-PreflightError -Code "$errorPrefix-GATE-DEFINITION-DRIFT-$gateIndex"
        }
        $expectedGateStatus = if ($expectedGate.id -ceq "INDEPENDENT_INTEGRATION_REVIEW_PASS") {
            if ($reviewState -ceq "PASS") { "PASS" } else { "BLOCKED" }
        }
        elseif ($expectedGate.id -cin @(
            "PRE_HANDOFF_AUDIT_PASS",
            "QA_DISPATCHABLE"
        )) {
            "BLOCKED"
        }
        else {
            "PASS"
        }
        if ((Get-ContractProperty $gate "status") -cne $expectedGateStatus) {
            Add-PreflightError -Code "$errorPrefix-GATE-STATUS-INVALID-$($expectedGate.id)"
        }
    }

    $sourcePrompts = Get-ContractProperty $Contract "prompts"
    $referencePrompts = Get-ContractProperty $ReferenceContract "prompts"
    foreach ($promptName in @("toolRepair", "padQa")) {
        $sourcePrompt = Get-ContractProperty $sourcePrompts $promptName
        $referencePrompt = Get-ContractProperty $referencePrompts $promptName
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $sourcePrompt `
            -ExpectedProperties @(
                "roleId",
                "status",
                "dispatchable",
                "retiredReason",
                "actionClass",
                "command",
                "firstAction",
                "requiredTokens",
                "textLines"
            ))) {
            Add-PreflightError -Code "$errorPrefix-PROMPT-SCHEMA-INVALID-$promptName"
            continue
        }
        foreach ($contentField in @(
            "roleId",
            "actionClass",
            "command",
            "firstAction",
            "requiredTokens",
            "textLines"
        )) {
            if (
                (Get-CanonicalJsonText -Value (
                    Get-ContractProperty $sourcePrompt $contentField
                )) -cne
                (Get-CanonicalJsonText -Value (
                    Get-ContractProperty $referencePrompt $contentField
                ))
            ) {
                Add-PreflightError -Code "$errorPrefix-PROMPT-CONTENT-DRIFT-$promptName-$contentField"
            }
        }
    }
    $toolRepairPrompt = Get-ContractProperty $sourcePrompts "toolRepair"
    $sourcePadQaPrompt = Get-ContractProperty $sourcePrompts "padQa"
    $expectedPreActivePromptRoleId = if (
        $preAuditState -ceq "PRE_REVIEW_PASS_AWAITING_AUDIT"
    ) {
        "PRE_HANDOFF_AUDIT_OWNER"
    }
    else {
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
    }
    if (
        (Get-ContractProperty $toolRepairPrompt "roleId") -cne
        $expectedPreActivePromptRoleId -or
        (Get-ContractProperty $toolRepairPrompt "status") -cne "ACTIVE" -or
        (Get-ContractProperty $toolRepairPrompt "dispatchable") -ne $true -or
        $null -ne (Get-ContractProperty $toolRepairPrompt "retiredReason")
    ) {
        Add-PreflightError -Code "$errorPrefix-TOOL-REPAIR-PROMPT-BOUNDARY-INVALID"
    }
    if (
        (Get-ContractProperty $sourcePadQaPrompt "roleId") -cne "PAD_QA_OWNER" -or
        (Get-ContractProperty $sourcePadQaPrompt "status") -cne
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT" -or
        (Get-ContractProperty $sourcePadQaPrompt "dispatchable") -ne $false -or
        $null -ne (Get-ContractProperty $sourcePadQaPrompt "retiredReason")
    ) {
        Add-PreflightError -Code "$errorPrefix-PAD-QA-PROMPT-BOUNDARY-INVALID"
    }
    if ((Get-ContractProperty $Contract "activePrompt") -cne "toolRepair") {
        Add-PreflightError -Code "$errorPrefix-ACTIVE-PROMPT-MUST-NOT-BE-PADQA"
    }
    $expectedNextPointer = (
        $SourceContractRelativePath + "#/prompts/toolRepair/textLines"
    )
    $expectedQaPointer = (
        $SourceContractRelativePath + "#/prompts/padQa/textLines"
    )
    if (
        (Get-ContractProperty $Contract "next_prompt_path") -cne
        $expectedNextPointer -or
        (Get-ContractProperty $Contract "nextPromptPath") -cne
        $expectedNextPointer -or
        (Get-ContractProperty $Contract "qa_prompt_path") -cne
        $expectedQaPointer -or
        (Get-ContractProperty $Contract "qaPromptPath") -cne
        $expectedQaPointer
    ) {
        Add-PreflightError -Code "$errorPrefix-PROMPT-POINTER-INVALID"
    }
    return [pscustomobject]@{
        substate = $preAuditState
        expectedStatus = $expectedSourceRoundStatus
        reviewGateStatus = if ($preAuditState -ceq "PRE_REVIEW_PASS_AWAITING_AUDIT") {
            "PASS"
        }
        else {
            "BLOCKED"
        }
    }
}

$script:branchValue = "unknown"
$script:headValue = "unknown"
$script:dirtyCount = -1
$script:roleCount = 0
$script:gateCount = 0
$script:toolsEntryCount = 0
$script:toolsDigestValue = "unknown"
$script:tasksInProgressCount = 0
$script:handoffPhaseValue = "unknown"
$script:preAuditSubstateValue = "unknown"
$script:auditReceiptState = "UNKNOWN"
$script:transitionPathCount = 0
$script:currentTransitionPathCount = 0

try {
    if ($PSVersionTable.PSVersion.Major -lt 7) {
        Add-PreflightError -Code "POWERSHELL7_REQUIRED"
    }

    if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
        $repositoryRoot = [System.IO.Path]::GetFullPath(
            (Join-Path $PSScriptRoot "..\..")
        )
    }
    else {
        $repositoryRoot = [System.IO.Path]::GetFullPath($RepoRoot)
    }

    if (-not (Test-Path -LiteralPath $repositoryRoot -PathType Container)) {
        Add-PreflightError -Code "REPO_ROOT_MISSING"
        throw "Repository root is unavailable."
    }

    if ([string]::IsNullOrWhiteSpace($ContractPath)) {
        $contractFullPath = Join-Path $PSScriptRoot "HANDOFF_BOOTSTRAP_V2.json"
    }
    elseif ([System.IO.Path]::IsPathRooted($ContractPath)) {
        $contractFullPath = [System.IO.Path]::GetFullPath($ContractPath)
    }
    else {
        $contractFullPath = [System.IO.Path]::GetFullPath(
            (Join-Path $repositoryRoot $ContractPath)
        )
    }

    $scriptText = Read-StrictUtf8LfFile `
        -AbsolutePath $PSCommandPath `
        -ErrorPrefix "PREFLIGHT-SCRIPT"
    [void]$scriptText

    $contractText = Read-StrictUtf8LfFile `
        -AbsolutePath $contractFullPath `
        -ErrorPrefix "CONTRACT"
    if ($null -eq $contractText) {
        throw "Contract cannot be read."
    }

    try {
        $contractData = $contractText | ConvertFrom-Json -DateKind String
    }
    catch {
        Add-PreflightError -Code "CONTRACT-JSON-INVALID"
        throw "Contract JSON is invalid."
    }

    $requiredTopLevelNames = @(
        "contractVersion",
        "schema",
        "generatedAt",
        "inputBaseline",
        "handoffFlags",
        "evidenceRoot",
        "evidence",
        "qaRoundState",
        "auditReceipt",
        "acceptedArtifacts",
        "qaTools",
        "devicePolicy",
        "productGates",
        "readOrder",
        "rejectedScope",
        "roles",
        "requiredGates",
        "stopConditions",
        "firstReadOnlyActions",
        "activePrompt",
        "next_prompt_path",
        "qa_prompt_path",
        "nextPromptPath",
        "qaPromptPath",
        "prompts",
        "validation"
    )
    foreach ($name in $requiredTopLevelNames) {
        if ($null -eq $contractData.PSObject.Properties[$name]) {
            Add-PreflightError -Code "TOP-LEVEL-MISSING-$name"
        }
    }
    $actualTopLevelNames = [string[]]@(
        $contractData.PSObject.Properties | ForEach-Object { $_.Name }
    )
    if (
        $actualTopLevelNames.Count -ne $requiredTopLevelNames.Count -or
        -not (Test-OrdinalSetEqual `
            -Actual $actualTopLevelNames `
            -Expected ([string[]]$requiredTopLevelNames))
    ) {
        Add-PreflightError -Code "TOP-LEVEL-SET-INVALID"
    }
    if ($validationErrors.Count -gt 0) {
        throw "Required top-level fields are missing."
    }

    if (
        (Get-ContractProperty $contractData "contractVersion") -cne "HANDOFF_BOOTSTRAP_V2" -or
        (Get-ContractProperty $contractData "schema") -cne "aurorama.handoff-bootstrap.v2"
    ) {
        Add-PreflightError -Code "CONTRACT-IDENTITY-INVALID"
    }

    $validation = Get-ContractProperty $contractData "validation"
    $handoffPhase = [string](
        Get-ContractProperty $validation "handoffPhase"
    )
    $script:handoffPhaseValue = if (
        [string]::IsNullOrWhiteSpace($handoffPhase)
    ) {
        "unknown"
    }
    else {
        $handoffPhase
    }
    if ($handoffPhase -cnotin @(
        "PRE_HANDOFF_AUDIT",
        "POST_HANDOFF_AUDIT_READY",
        "POST_QA_RUNTIME_DEFERRED"
    )) {
        Add-PreflightError -Code "HANDOFF-PHASE-UNKNOWN"
    }
    $isPostQaDeferredPhase = $handoffPhase -ceq "POST_QA_RUNTIME_DEFERRED"
    $isPostAuditPhase = $handoffPhase -cin @(
        "POST_HANDOFF_AUDIT_READY",
        "POST_QA_RUNTIME_DEFERRED"
    )
    $auditReceipt = Get-ContractProperty $contractData "auditReceipt"
    $auditedSourceCommit = ""
    if (
        (-not $isPostAuditPhase -and $null -ne $auditReceipt) -or
        ($isPostAuditPhase -and $null -eq $auditReceipt)
    ) {
        Add-PreflightError -Code "AUDIT-RECEIPT-PHASE-MISMATCH"
    }

    $inputBaseline = Get-ContractProperty $contractData "inputBaseline"
    $inputCommit = Get-ContractProperty $inputBaseline "commit"
    $inputBranch = Get-ContractProperty $inputBaseline "branch"
    $inputWorktree = Get-ContractProperty $inputBaseline "worktree"
    $qaV8HandoffHead = Get-ContractProperty $inputBaseline "qaV8HandoffHead"
    if (
        -not [System.IO.Path]::IsPathFullyQualified([string]$inputWorktree) -or
        -not $inputWorktree.Equals(
            $repositoryRoot,
            [System.StringComparison]::OrdinalIgnoreCase
        )
    ) {
        Add-PreflightError -Code "WORKTREE-IDENTITY-MISMATCH"
    }
    foreach ($shaValue in @($inputCommit, $qaV8HandoffHead)) {
        if ($shaValue -notmatch "^[0-9a-f]{40}$") {
            Add-PreflightError -Code "INPUT-COMMIT-INVALID"
        }
    }

    $flags = Get-ContractProperty $contractData "handoffFlags"
    $expectedFlags = [ordered]@{
        SUBAGENT_FIRST = "YES"
        ONE_PHASE_PER_FRESH_AGENT = "YES"
        NO_REUSE_SUBAGENT = "YES"
        ROOT_INTEGRATION_ONLY = "YES"
    }
    $actualFlagNames = @(
        $flags.PSObject.Properties | ForEach-Object { $_.Name }
    )
    if (-not (Test-OrdinalSetEqual `
        -Actual $actualFlagNames `
        -Expected ([string[]]$expectedFlags.Keys))) {
        Add-PreflightError -Code "HANDOFF-FLAG-SET-INVALID"
    }
    foreach ($flagName in $expectedFlags.Keys) {
        $flagValue = Get-ContractProperty $flags $flagName
        if ($flagValue -cne $expectedFlags[$flagName]) {
            Add-PreflightError -Code "HANDOFF-FLAG-INVALID-$flagName"
        }
    }

    $devicePolicy = Get-ContractProperty $contractData "devicePolicy"
    if (
        (Get-ContractProperty $devicePolicy "DEVICE_ORDER") -cne "Pad->K60" -or
        (Get-ContractProperty $devicePolicy "DEVICE_WINDOW_MINUTES") -ne 10
    ) {
        Add-PreflightError -Code "DEVICE-POLICY-DRIFT"
    }
    if ((Get-ContractProperty $devicePolicy "oldThirtyMinuteEvidenceReusable") -ne $false) {
        Add-PreflightError -Code "OLD-30-MINUTE-EVIDENCE-REUSABLE"
    }

    $devices = @(Get-ContractProperty $devicePolicy "devices")
    $expectedDevices = @(
        [pscustomobject]@{
            order = 1
            name = "Pad"
            serial = "43af8627"
            registered = $true
        },
        [pscustomobject]@{
            order = 2
            name = "K60"
            serial = "8e875894"
            registered = $false
        }
    )
    if ($devices.Count -ne $expectedDevices.Count) {
        Add-PreflightError -Code "DEVICE-COUNT-INVALID"
    }
    else {
        for ($deviceIndex = 0; $deviceIndex -lt $expectedDevices.Count; $deviceIndex++) {
            $actualDevice = $devices[$deviceIndex]
            $expectedDevice = $expectedDevices[$deviceIndex]
            foreach ($fieldName in @("order", "name", "serial", "registered")) {
                if (
                    (Get-ContractProperty $actualDevice $fieldName) -cne
                    (Get-ContractProperty $expectedDevice $fieldName)
                ) {
                    Add-PreflightError -Code "DEVICE-ENTRY-INVALID-$deviceIndex-$fieldName"
                }
            }
        }
    }
    if ((Get-ContractProperty $devicePolicy "maximumAudioGapSeconds") -ne 15) {
        Add-PreflightError -Code "MAX-AUDIO-GAP-NOT-15"
    }

    $expectedProductGates = @(
        "fresh-install-and-device-profile-identity",
        "real-audiotrack-start-completion",
        "head-middle-tail-continuity",
        "maximum-audio-gap-lte-15s",
        "foreground-service-mediasession-notification",
        "pid-lifecycle-and-resource-convergence",
        "current-run-native-java-crash-anr-inputtimeout-gate",
        "audio-focus-and-single-audio-owner",
        "voice-profiles-zf_001-zm_010",
        "cleanup-and-capture-restore"
    )
    $actualProductGates = @(
        Get-ContractProperty $contractData "productGates"
    )
    if (-not (Test-OrdinalSetEqual `
        -Actual $actualProductGates `
        -Expected $expectedProductGates)) {
        Add-PreflightError -Code "PRODUCT-GATE-SET-INVALID"
    }

    $qaRoundState = Get-ContractProperty $contractData "qaRoundState"
    $qaRoundStateProperties = @(
        "status",
        "lastQaVerdict",
        "round1IntegrationReview",
        "round2IntegrationReview",
        "independentIntegrationReview",
        "preHandoffAudit",
        "padQaPromptDispatch",
        "productFindings",
        "coreStepsExecuted",
        "padTenMinuteCoreRun",
        "k60Registration",
        "k60CoreRun",
        "cleanup",
        "padLockActiveCount"
    )
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $qaRoundState `
        -ExpectedProperties $qaRoundStateProperties)) {
        Add-PreflightError -Code "QA-ROUND-STATE-SCHEMA-INVALID"
    }
    $independentReviewState = [string](
        Get-ContractProperty $qaRoundState "independentIntegrationReview"
    )
    $preAuditSubstate = if (
        $independentReviewState -ceq "BLOCKED_PENDING_ROUND9_REVIEW"
    ) {
        "PRE_REVIEW_PENDING"
    }
    elseif ($independentReviewState -ceq "PASS") {
        "PRE_REVIEW_PASS_AWAITING_AUDIT"
    }
    else {
        "INVALID"
    }
    $preExpectedRoundStatus = switch ($preAuditSubstate) {
        "PRE_REVIEW_PENDING" {
            "ROUND8_PASS_PRE_STATE_REPAIRED_AWAITING_FRESH_ROUND9_REVIEW"
        }
        "PRE_REVIEW_PASS_AWAITING_AUDIT" {
            "ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT"
        }
        default {
            "INVALID"
        }
    }
    $preReviewGateStatus = if (
        $preAuditSubstate -ceq "PRE_REVIEW_PENDING"
    ) {
        "BLOCKED"
    }
    elseif ($preAuditSubstate -ceq "PRE_REVIEW_PASS_AWAITING_AUDIT") {
        "PASS"
    }
    else {
        "INVALID"
    }
    if (
        -not $isPostAuditPhase -and
        $preReviewGateStatus -ceq "INVALID"
    ) {
        Add-PreflightError -Code "INDEPENDENT-REVIEW-STATE-INVALID"
    }
    $expectedQaRoundState = [ordered]@{
        status = if ($isPostQaDeferredPhase) {
            "RUNTIME_QA_DEFERRED_TO_G2"
        }
        elseif ($isPostAuditPhase) {
            "POST_HANDOFF_AUDIT_READY"
        }
        else {
            $preExpectedRoundStatus
        }
        lastQaVerdict = "BLOCKED_TOOL_NONCONVERGENCE"
        round1IntegrationReview = "FAIL_1_P1_2_P2_REPAIRED_CLOSED_BY_ROUND2"
        round2IntegrationReview = "FAIL_P2_PREFLIGHT_SEMANTIC_BYPASS_REPAIRED_CLOSED_BY_ROUND3_PASS"
        independentIntegrationReview = if ($isPostAuditPhase) {
            "PASS"
        }
        else {
            $independentReviewState
        }
        preHandoffAudit = if ($isPostAuditPhase) {
            "PASS"
        }
        else {
            "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
        }
        padQaPromptDispatch = if ($isPostQaDeferredPhase) {
            "DEFERRED_TO_G2"
        }
        elseif ($isPostAuditPhase) {
            "PASS"
        }
        else {
            "BLOCKED"
        }
        coreStepsExecuted = if ($isPostQaDeferredPhase) { 1 } else { 0 }
        padTenMinuteCoreRun = if ($isPostQaDeferredPhase) {
            "PARTIAL_TOOL_BLOCKED"
        }
        else {
            "NOT_RUN"
        }
        k60Registration = if ($isPostQaDeferredPhase) {
            "LOCKED_NOT_REGISTERED"
        }
        else {
            "NOT_REGISTERED"
        }
        k60CoreRun = if ($isPostQaDeferredPhase) {
            "NOT_RUN_LOCKED"
        }
        else {
            "NOT_RUN"
        }
        cleanup = "PASS_CLEANUP_AND_RESTORE"
        padLockActiveCount = 0
    }
    foreach ($stateName in $expectedQaRoundState.Keys) {
        if (
            (Get-ContractProperty $qaRoundState $stateName) -cne
            $expectedQaRoundState[$stateName]
        ) {
            Add-PreflightError -Code "QA-ROUND-STATE-MISMATCH-$stateName"
        }
    }
    $productFindings = Get-ContractProperty $qaRoundState "productFindings"
    if (-not (Test-ExactObjectProperties `
        -ObjectValue $productFindings `
        -ExpectedProperties @("P0", "P1", "P2", "P3"))) {
        Add-PreflightError -Code "PRODUCT-FINDINGS-SCHEMA-INVALID"
    }
    foreach ($severity in @("P0", "P1", "P2", "P3")) {
        if ((Get-ContractProperty $productFindings $severity) -cne "NOT_ASSESSED") {
            Add-PreflightError -Code "PRODUCT-FINDINGS-NOT-ASSESSED-$severity"
        }
    }

    $roleObjects = @(Get-ContractProperty $contractData "roles")
    $script:roleCount = $roleObjects.Count
    Assert-RoleCatalog `
        -RolesValue (Get-ContractProperty $contractData "roles") `
        -ExpectedSpecs $immutableRoleSpecs `
        -ErrorPrefix "ROLE"

    $activePromptName = [string](Get-ContractProperty $contractData "activePrompt")
    $allowedPromptNames = @("toolRepair", "padQa")
    $allowedActivePromptNames = @($allowedPromptNames + "none")
    if ($activePromptName -notin $allowedActivePromptNames) {
        Add-PreflightError -Code "ACTIVE-PROMPT-UNKNOWN"
    }
    $expectedActivePromptName = if ($isPostQaDeferredPhase) {
        "none"
    }
    elseif ($isPostAuditPhase) {
        "padQa"
    }
    else {
        "toolRepair"
    }
    if ($activePromptName -cne $expectedActivePromptName) {
        Add-PreflightError -Code "ACTIVE-PROMPT-PHASE-MISMATCH"
    }

    $canonicalPreflightPath = [System.IO.Path]::GetFullPath($PSCommandPath)
    $quotedCanonicalPreflightPath = "'" + $canonicalPreflightPath.Replace("'", "''") + "'"
    $expectedPromptCommands = @{
        toolRepair = [string[]]@(
            "pwsh",
            "-NoProfile",
            "-File",
            $canonicalPreflightPath,
            "-EmitPrompt"
        )
        padQa = [string[]]@(
            "pwsh",
            "-NoProfile",
            "-File",
            $canonicalPreflightPath,
            "-Prompt",
            "padQa",
            "-EmitPrompt"
        )
    }
    $expectedPromptActionTexts = @{
        toolRepair = "pwsh -NoProfile -File $quotedCanonicalPreflightPath -EmitPrompt"
        padQa = "pwsh -NoProfile -File $quotedCanonicalPreflightPath -Prompt padQa -EmitPrompt"
    }

    $promptsObject = Get-ContractProperty $contractData "prompts"
    foreach ($promptName in $allowedPromptNames) {
        $promptObject = Get-ContractProperty $promptsObject $promptName
        if ($null -eq $promptObject) {
            Add-PreflightError -Code "PROMPT-MISSING-$promptName"
            continue
        }
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $promptObject `
            -ExpectedProperties @(
                "roleId",
                "status",
                "dispatchable",
                "retiredReason",
                "actionClass",
                "command",
                "firstAction",
                "requiredTokens",
                "textLines"
            ))) {
            Add-PreflightError -Code "PROMPT-SCHEMA-INVALID-$promptName"
        }
        $promptStatus = Get-ContractProperty $promptObject "status"
        $allowedPromptStatuses = @(
            "ACTIVE",
            "BLOCKED_PENDING_PRE_HANDOFF_AUDIT",
            "RETIRED_AFTER_AUDIT_PASS",
            "RETIRED_RUNTIME_QA_DEFERRED_TO_G2"
        )
        if ($promptStatus -notin $allowedPromptStatuses) {
            Add-PreflightError -Code "PROMPT-STATUS-UNKNOWN-$promptName"
        }
        $promptRoleId = [string](Get-ContractProperty $promptObject "roleId")
        $expectedPromptRoleId = if ($promptName -eq "toolRepair") {
            if (
                $isPostAuditPhase -or
                $preAuditSubstate -ceq "PRE_REVIEW_PASS_AWAITING_AUDIT"
            ) {
                "PRE_HANDOFF_AUDIT_OWNER"
            }
            else {
                "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
            }
        }
        else {
            "PAD_QA_OWNER"
        }
        if ($promptRoleId -cne $expectedPromptRoleId) {
            Add-PreflightError -Code "PROMPT-ROLE-MISMATCH-$promptName"
        }
        $promptDispatchable = Get-ContractProperty $promptObject "dispatchable"
        $expectedPromptDispatchable = if ($isPostQaDeferredPhase) {
            $false
        }
        elseif ($isPostAuditPhase) {
            $promptName -eq "padQa"
        }
        else {
            $promptName -eq "toolRepair"
        }
        if ($promptDispatchable -ne $expectedPromptDispatchable) {
            Add-PreflightError -Code "PROMPT-DISPATCH-BOUNDARY-INVALID-$promptName"
        }
        $expectedPromptStatus = if ($isPostQaDeferredPhase) {
            if ($promptName -eq "padQa") {
                "RETIRED_RUNTIME_QA_DEFERRED_TO_G2"
            }
            else {
                "RETIRED_AFTER_AUDIT_PASS"
            }
        }
        elseif ($isPostAuditPhase) {
            if ($promptName -eq "padQa") {
                "ACTIVE"
            }
            else {
                "RETIRED_AFTER_AUDIT_PASS"
            }
        }
        else {
            if ($promptName -eq "padQa") {
                "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
            }
            else {
                "ACTIVE"
            }
        }
        if ($promptStatus -cne $expectedPromptStatus) {
            Add-PreflightError -Code "PROMPT-STATUS-MISMATCH-$promptName"
        }
        $retiredReason = Get-ContractProperty $promptObject "retiredReason"
        if ($isPostQaDeferredPhase) {
            if (
                -not (Test-NonEmptyString $retiredReason)
            ) {
                Add-PreflightError -Code "PROMPT-RETIRED-REASON-MISSING-$promptName"
            }
        }
        elseif ($isPostAuditPhase) {
            if (
                $promptName -eq "toolRepair" -and
                (-not (Test-NonEmptyString $retiredReason))
            ) {
                Add-PreflightError -Code "PROMPT-RETIRED-REASON-MISSING-$promptName"
            }
            elseif (
                $promptName -eq "padQa" -and
                $null -ne $retiredReason
            ) {
                Add-PreflightError -Code "PROMPT-RETIRED-REASON-UNEXPECTED-$promptName"
            }
        }
        elseif ($null -ne $retiredReason) {
            Add-PreflightError -Code "PROMPT-RETIRED-REASON-UNEXPECTED-$promptName"
        }
        if (
            (Get-ContractProperty $promptObject "actionClass") -cne
            "READ_ONLY_PREFLIGHT"
        ) {
            Add-PreflightError -Code "PROMPT-ACTION-CLASS-INVALID-$promptName"
        }
        if (-not (Test-OrdinalStringArrayEqual `
            -Actual (Get-ContractProperty $promptObject "command") `
            -Expected $expectedPromptCommands[$promptName])) {
            Add-PreflightError -Code "PROMPT-COMMAND-INVALID-$promptName"
        }
        if (
            (Get-ContractProperty $promptObject "firstAction") -cne
            $expectedPromptActionTexts[$promptName]
        ) {
            Add-PreflightError -Code "PROMPT-FIRST-ACTION-TEXT-INVALID-$promptName"
        }
        $promptText = Get-PromptText `
            -PromptValue $promptObject `
            -ErrorPrefix "PROMPT-$promptName"
        if ($null -ne $promptText) {
            $expectedActionLead = (
                "第一条只读动作必须实际执行：" +
                $expectedPromptActionTexts[$promptName]
            )
            $promptLines = @(Get-ContractProperty $promptObject "textLines")
            if (
                $promptLines.Count -lt 3 -or
                $promptLines[2] -isnot [string] -or
                -not $promptLines[2].StartsWith(
                    $expectedActionLead,
                    [System.StringComparison]::Ordinal
                )
            ) {
                Add-PreflightError -Code "PROMPT-FIRST-ACTION-LINE-INVALID-$promptName"
            }
            elseif (
                $promptLines[2].Length -le $expectedActionLead.Length -or
                $promptLines[2][$expectedActionLead.Length] -cne [char]"。"
            ) {
                Add-PreflightError -Code "PROMPT-FIRST-ACTION-TRAILING-ARGUMENT-$promptName"
            }
            $pwshCommandCount = [regex]::Matches(
                $promptText,
                "(?i)(?<![A-Za-z0-9_])pwsh(?=\s)"
            ).Count
            if ($pwshCommandCount -ne 1) {
                Add-PreflightError -Code "PROMPT-COMMAND-COUNT-NOT-ONE-$promptName"
            }
        }
        $requiredPromptTokens = @(
            Get-ContractProperty $promptObject "requiredTokens"
        )
        if (-not (Test-StringArray -Value $requiredPromptTokens)) {
            Add-PreflightError -Code "PROMPT-TOKENS-INVALID-$promptName"
        }
        else {
            Assert-Tokens `
                -TextValue $promptText `
                -Tokens $requiredPromptTokens `
                -ErrorPrefix "PROMPT-$promptName"
        }
    }
    $mandatoryPromptTokens = @(
        "SUBAGENT_FIRST=YES",
        "ONE_PHASE_PER_FRESH_AGENT=YES",
        "NO_REUSE_SUBAGENT=YES",
        "ROOT_INTEGRATION_ONLY=YES",
        "DEVICE_WINDOW_MINUTES=10",
        "DEVICE_ORDER=Pad->K60",
        "READ_ONLY_PREFLIGHT",
        "BLOCKED"
    )
    foreach ($promptName in $allowedPromptNames) {
        $promptObject = Get-ContractProperty $promptsObject $promptName
        $promptText = Get-PromptText `
            -PromptValue $promptObject `
            -ErrorPrefix "MANDATORY-PROMPT-$promptName"
        Assert-Tokens `
            -TextValue $promptText `
            -Tokens $mandatoryPromptTokens `
            -ErrorPrefix "MANDATORY-PROMPT-$promptName"
    }
    $padQaPromptText = Get-PromptText `
        -PromptValue (Get-ContractProperty $promptsObject "padQa") `
        -ErrorPrefix "MANDATORY-PAD-QA-PROMPT"
    Assert-Tokens `
        -TextValue $padQaPromptText `
        -Tokens @(
            "qa-v9",
            "43af8627",
            "8e875894",
            "AudioTrack",
            "15 秒",
            "ApplicationExitInfo",
            "cleanup",
            "ACTIVE=0",
            "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4"
        ) `
        -ErrorPrefix "MANDATORY-PAD-QA-PROMPT"
    $toolRepairPrompt = Get-ContractProperty $promptsObject "toolRepair"
    $expectedToolRepairPromptStatus = if ($isPostAuditPhase) {
        "RETIRED_AFTER_AUDIT_PASS"
    }
    else {
        "ACTIVE"
    }
    if (
        (Get-ContractProperty $toolRepairPrompt "status") -cne
        $expectedToolRepairPromptStatus
    ) {
        Add-PreflightError -Code "TOOL-REPAIR-PROMPT-STATUS-INVALID"
    }
    $padQaPrompt = Get-ContractProperty $promptsObject "padQa"
    $expectedPadQaPromptStatus = if ($isPostQaDeferredPhase) {
        "RETIRED_RUNTIME_QA_DEFERRED_TO_G2"
    }
    elseif ($isPostAuditPhase) {
        "ACTIVE"
    }
    else {
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    }
    if (
        (Get-ContractProperty $padQaPrompt "status") -cne
        $expectedPadQaPromptStatus
    ) {
        Add-PreflightError -Code "PAD-QA-PROMPT-DISPATCH-BOUNDARY-INVALID"
    }
    $padQaPromptTextForStaleCheck = Get-PromptText `
        -PromptValue $padQaPrompt `
        -ErrorPrefix "PAD-QA-PROMPT-STALE-CHECK"
    foreach ($staleToken in @(
        "尚未 commit",
        "未提交 / ignored",
        "force-add 集成说明"
    )) {
        if (
            $null -ne $padQaPromptTextForStaleCheck -and
            $padQaPromptTextForStaleCheck.Contains(
                $staleToken,
                [System.StringComparison]::Ordinal
            )
        ) {
            Add-PreflightError -Code "PAD-QA-PROMPT-STALE-COMMIT-STATE"
        }
    }

    $selectedPromptName = if ($Prompt -eq "next") {
        $activePromptName
    }
    else {
        $Prompt
    }
    if ($selectedPromptName -cne $activePromptName) {
        Add-PreflightError -Code "PROMPT-NOT-ACTIVE"
    }

    $nextPromptPath = [string](Get-ContractProperty $contractData "nextPromptPath")
    $qaPromptPath = [string](Get-ContractProperty $contractData "qaPromptPath")
    $snakeNextPromptPath = [string](Get-ContractProperty $contractData "next_prompt_path")
    $snakeQaPromptPath = [string](Get-ContractProperty $contractData "qa_prompt_path")
    if ($snakeNextPromptPath -cne $nextPromptPath) {
        Add-PreflightError -Code "NEXT-PROMPT-PATH-MISMATCH"
    }
    if ($snakeQaPromptPath -cne $qaPromptPath) {
        Add-PreflightError -Code "QA-PROMPT-PATH-MISMATCH"
    }
    if (
        $isPostQaDeferredPhase -and
        (
            $nextPromptPath -cne "docs/V2_PLAN.md#ACTIVE" -or
            $snakeNextPromptPath -cne "docs/V2_PLAN.md#ACTIVE"
        )
    ) {
        Add-PreflightError -Code "DEFERRED-NEXT-PROMPT-PATH-INVALID"
    }
    $activePromptPointerPairs = if ($isPostQaDeferredPhase) {
        @(
            @{
                Name = "QA"
                Pointer = $qaPromptPath
                ExpectedSuffix = "/prompts/padQa/textLines"
            }
        )
    }
    else {
        @(
            @{
                Name = "NEXT"
                Pointer = $nextPromptPath
                ExpectedSuffix = "/prompts/$activePromptName/textLines"
            },
            @{
                Name = "QA"
                Pointer = $qaPromptPath
                ExpectedSuffix = "/prompts/padQa/textLines"
            }
        )
    }
    foreach ($promptPointerPair in @(
        $activePromptPointerPairs
    )) {
        $pointerValue = $promptPointerPair.Pointer
        if ($pointerValue -notmatch "^([^#]+)#(/.*)$") {
            Add-PreflightError -Code "PROMPT-POINTER-INVALID-$($promptPointerPair.Name)"
            continue
        }
        $pointerBase = $Matches[1]
        $pointerFragment = $Matches[2]
        if (-not $pointerFragment.EndsWith(
            $promptPointerPair.ExpectedSuffix,
            [System.StringComparison]::Ordinal
        )) {
            Add-PreflightError -Code "PROMPT-POINTER-SUFFIX-INVALID-$($promptPointerPair.Name)"
        }
        $pointerFile = Resolve-RepoFile `
            -RelativeOrAbsolutePath $pointerBase `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "PROMPT-POINTER-FILE-$($promptPointerPair.Name)"
        if ($null -ne $pointerFile -and -not (Test-Path -LiteralPath $pointerFile -PathType Leaf)) {
            Add-PreflightError -Code "PROMPT-POINTER-FILE-MISSING-$($promptPointerPair.Name)"
        }
        $pointerValueFromContract = Get-JsonPointerValue `
            -RootValue $contractData `
            -JsonPointer ("#" + $pointerFragment)
        if (-not (Test-StringArray -Value $pointerValueFromContract)) {
            Add-PreflightError -Code "PROMPT-POINTER-TARGET-INVALID-$($promptPointerPair.Name)"
        }
    }

    $firstReadOnlyActions = Get-ContractProperty $contractData "firstReadOnlyActions"
    foreach ($promptName in @("toolRepair", "padQa")) {
        $actionList = @(Get-ContractProperty $firstReadOnlyActions $promptName)
        if (-not (Test-StringArray -Value $actionList) -or $actionList.Count -lt 2) {
            Add-PreflightError -Code "FIRST-ACTION-INVALID-$promptName"
            continue
        }
        if ($actionList[0] -cne $expectedPromptActionTexts[$promptName]) {
            Add-PreflightError -Code "FIRST-ACTION-NOT-CANONICAL-$promptName"
        }
        if (
            $promptName -eq "toolRepair" -and
            -not $actionList[1].Contains(
                $(if (
                    $isPostAuditPhase -or
                    $preAuditSubstate -ceq "PRE_REVIEW_PASS_AWAITING_AUDIT"
                ) {
                    "PRE_HANDOFF_AUDIT_OWNER"
                }
                else {
                    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
                }),
                [System.StringComparison]::Ordinal
            )
        ) {
            Add-PreflightError -Code "FIRST-ACTION-NEXT-ROLE-INVALID"
        }
    }

    $readOrder = @(Get-ContractProperty $contractData "readOrder")
    if ($readOrder.Count -eq 0) {
        Add-PreflightError -Code "READ-ORDER-EMPTY"
    }
    $seenReadOrderPaths = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $expectedReadOrderNumber = 1
    foreach ($readEntry in $readOrder) {
        if ((Get-ContractProperty $readEntry "order") -ne $expectedReadOrderNumber) {
            Add-PreflightError -Code "READ-ORDER-SEQUENCE-INVALID"
        }
        $expectedReadOrderNumber++
        $readPath = [string](Get-ContractProperty $readEntry "path")
        $readSection = Get-ContractProperty $readEntry "section"
        if (
            -not (Test-NonEmptyString $readSection) -or
            -not $seenReadOrderPaths.Add($readPath)
        ) {
            Add-PreflightError -Code "READ-ORDER-ENTRY-INVALID"
        }
        $readFullPath = Resolve-RepoFile `
            -RelativeOrAbsolutePath $readPath `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "READ-ORDER-PATH"
        if ($null -ne $readFullPath -and -not (Test-Path -LiteralPath $readFullPath -PathType Leaf)) {
            Add-PreflightError -Code "READ-ORDER-PATH-MISSING"
        }
    }

    $rejectedScope = Get-ContractProperty $contractData "rejectedScope"
    foreach ($scopeName in @("readDenied", "executionDenied", "evidenceReuseDenied")) {
        if (-not (Test-StringArray -Value (Get-ContractProperty $rejectedScope $scopeName))) {
            Add-PreflightError -Code "REJECTED-SCOPE-INVALID-$scopeName"
        }
    }

    $requiredGateContainer = Get-ContractProperty $contractData "requiredGates"
    $expectedRequiredGateIds = @(
        "PREFLIGHT_PASS",
        "TOOL_NONCONVERGENCE_REPAIRED",
        "CONTRACT_DIGEST_REFRESHED",
        "INDEPENDENT_INTEGRATION_REVIEW_PASS",
        "PRE_HANDOFF_AUDIT_PASS",
        "QA_DISPATCHABLE"
    )
    $expectedGateOwners = @(
        "ROOT_INTEGRATION",
        "TOOL_REPAIR_OWNER",
        "DOC_PROTOCOL_OWNER",
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
        "PRE_HANDOFF_AUDIT_OWNER",
        "PAD_QA_OWNER"
    )
    $expectedGateRequirements = @{
        PREFLIGHT_PASS = [string[]]@()
        TOOL_NONCONVERGENCE_REPAIRED = [string[]]@("PREFLIGHT_PASS")
        CONTRACT_DIGEST_REFRESHED = [string[]]@(
            "TOOL_NONCONVERGENCE_REPAIRED"
        )
        INDEPENDENT_INTEGRATION_REVIEW_PASS = [string[]]@(
            "CONTRACT_DIGEST_REFRESHED"
        )
        PRE_HANDOFF_AUDIT_PASS = [string[]]@(
            "INDEPENDENT_INTEGRATION_REVIEW_PASS"
        )
        QA_DISPATCHABLE = [string[]]@("PRE_HANDOFF_AUDIT_PASS")
    }
    $expectedGateStatuses = if ($isPostQaDeferredPhase) {
        @(
            "PASS",
            "PASS",
            "PASS",
            "PASS",
            "PASS",
            "DEFERRED_TO_G2"
        )
    }
    elseif ($isPostAuditPhase) {
        @(
            "PASS",
            "PASS",
            "PASS",
            "PASS",
            "PASS",
            "PASS"
        )
    }
    else {
        @(
            "PASS",
            "PASS",
            "PASS",
            $preReviewGateStatus,
            "BLOCKED",
            "BLOCKED"
        )
    }
    $allowedGateStatuses = @("PASS", "NOT_RUN", "BLOCKED", "DEFERRED_TO_G2")

    $gateContainersValid = (
        (Test-ExactObjectProperties `
            -ObjectValue $requiredGateContainer `
            -ExpectedProperties @("lifecycleVersion", "lifecycle")) -and
        (Get-ContractProperty $requiredGateContainer "lifecycleVersion") -ceq
        "AURORAMA_HANDOFF_GATE_ORDER_V2"
    )
    if (-not $gateContainersValid) {
        Add-PreflightError -Code "REQUIRED-GATE-CONTAINER-INVALID"
    }

    $gateLifecycleValue = Get-ContractProperty $requiredGateContainer "lifecycle"
    if ($gateLifecycleValue -isnot [System.Array]) {
        Add-PreflightError -Code "REQUIRED-GATE-LIFECYCLE-INVALID"
        $gateObjects = @()
    }
    else {
        $gateObjects = @($gateLifecycleValue)
    }
    $script:gateCount = $gateObjects.Count
    $actualRequiredGateIds = @(
        $gateObjects | ForEach-Object {
            [string](Get-ContractProperty $_ "id")
        }
    )
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual $actualRequiredGateIds `
        -Expected $expectedRequiredGateIds)) {
        Add-PreflightError -Code "REQUIRED-GATE-LIFECYCLE-ORDER-INVALID"
    }
    if ($actualRequiredGateIds.Count -ne $expectedRequiredGateIds.Count) {
        Add-PreflightError -Code "REQUIRED-GATE-COUNT-INVALID"
    }
    $uniqueActualGateIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($actualGateId in $actualRequiredGateIds) {
        if (-not $uniqueActualGateIds.Add($actualGateId)) {
            Add-PreflightError -Code "REQUIRED-GATE-DUPLICATE"
        }
    }

    $priorGateIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $gateStatusById = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::Ordinal
    )
    for ($gateIndex = 0; $gateIndex -lt $gateObjects.Count; $gateIndex++) {
        $gateObject = $gateObjects[$gateIndex]
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $gateObject `
            -ExpectedProperties @("id", "owner", "requiredBefore", "status"))) {
            Add-PreflightError -Code "REQUIRED-GATE-SCHEMA-INVALID-$gateIndex"
        }

        $gateId = [string](Get-ContractProperty $gateObject "id")
        $gateOwner = [string](Get-ContractProperty $gateObject "owner")
        $gateStatus = [string](Get-ContractProperty $gateObject "status")
        $gateRequirementsProperty = $gateObject.PSObject.Properties["requiredBefore"]
        $gateRequirementsValue = [string[]]@()
        if ($null -ne $gateRequirementsProperty) {
            $gateRequirementsValue = [string[]]$gateRequirementsProperty.Value
        }

        if ($gateIndex -lt $expectedRequiredGateIds.Count) {
            if ($gateId -cne $expectedRequiredGateIds[$gateIndex]) {
                Add-PreflightError -Code "REQUIRED-GATE-ID-ORDER-INVALID-$gateIndex"
            }
            if ($gateOwner -cne $expectedGateOwners[$gateIndex]) {
                Add-PreflightError -Code "REQUIRED-GATE-OWNER-INVALID-$gateId"
            }
            if (-not (Test-OrdinalStringArrayEqual `
                -Actual $gateRequirementsValue `
                -Expected ([string[]]($expectedGateRequirements[$gateId])))) {
                Add-PreflightError -Code "REQUIRED-GATE-REQUIRED-BEFORE-INVALID-$gateId"
            }
            if ($gateStatus -cne $expectedGateStatuses[$gateIndex]) {
                Add-PreflightError -Code "REQUIRED-GATE-STATUS-INVALID-$gateId"
            }
        }

        if ($gateStatus -cnotin $allowedGateStatuses) {
            Add-PreflightError -Code "REQUIRED-GATE-STATUS-UNKNOWN-$gateId"
        }
        if ($gateRequirementsValue -is [System.Array]) {
            foreach ($prerequisiteId in @($gateRequirementsValue)) {
                if (-not $priorGateIds.Contains([string]$prerequisiteId)) {
                    Add-PreflightError -Code "REQUIRED-GATE-DEPENDENCY-NOT-PRIOR-$gateId"
                    continue
                }
                if (
                    $gateStatus -ceq "PASS" -and
                    -not $gateStatusById.ContainsKey([string]$prerequisiteId)
                ) {
                    Add-PreflightError -Code "REQUIRED-GATE-PASS-DEPENDENCY-MISSING-$gateId"
                }
                elseif (
                    $gateStatus -ceq "PASS" -and
                    $gateStatusById[[string]$prerequisiteId] -cne "PASS"
                ) {
                    Add-PreflightError -Code "REQUIRED-GATE-PASS-BEFORE-PREREQUISITE-$gateId"
                }
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($gateId)) {
            [void]$priorGateIds.Add($gateId)
            $gateStatusById[$gateId] = $gateStatus
        }
    }
    $preHandoffAuditStateValue = [string](
        Get-ContractProperty $qaRoundState "preHandoffAudit"
    )
    $sourceContractData = $null
    if ($isPostAuditPhase) {
        if (
            $preHandoffAuditStateValue -cne "PASS" -or
            -not $gateStatusById.ContainsKey("PRE_HANDOFF_AUDIT_PASS") -or
            $gateStatusById["PRE_HANDOFF_AUDIT_PASS"] -cne "PASS"
        ) {
            Add-PreflightError -Code "AUDIT-PASS-GATE-RECEIPT-PHASE-INVALID"
        }
    }
    else {
        if (
            $preHandoffAuditStateValue -ceq "PASS" -or
            (
                $gateStatusById.ContainsKey("PRE_HANDOFF_AUDIT_PASS") -and
                $gateStatusById["PRE_HANDOFF_AUDIT_PASS"] -ceq "PASS"
            )
        ) {
            Add-PreflightError -Code "AUDIT-PASS-WITHOUT-POST-PHASE-RECEIPT"
        }
        if (
            $gateStatusById.ContainsKey("QA_DISPATCHABLE") -and
            $gateStatusById["QA_DISPATCHABLE"] -ceq "PASS"
        ) {
            Add-PreflightError -Code "QA-PASS-IN-PRE-AUDIT-PHASE"
        }
    }

    $stopConditionValues = @(
        Get-ContractProperty $contractData "stopConditions"
    )
    $minimumStopConditions = @(
        "PREFLIGHT_FAIL_CLOSED",
        "DEVICE_ORDER_WINDOW_SERIAL_OR_LOCK_DRIFT",
        "SHARED_WRITE_OVERLAP_OR_SAME_AGENT_REUSE",
        "TEN_MINUTE_WINDOW_EXTENSION_REQUEST",
        "K60_ATTEMPT_BEFORE_PAD_CORE_PASS",
        "OLD_EVIDENCE_OR_INSTALL_CLEANUP_USED_AS_PRODUCT_PASS",
        "NEW_P0_P2_OR_EVIDENCE_TAMPER",
        "CONTRACT_FIELD_MISSING_UNKNOWN_OR_ROLE_INCOMPLETE",
        "AUDIT_RECEIPT_MISSING_UNKNOWN_OR_BINDING_DRIFT",
        "POST_AUDIT_TRANSITION_PATH_OUTSIDE_ALLOWLIST"
    )
    foreach ($stopCondition in $minimumStopConditions) {
        if ($stopCondition -cnotin $stopConditionValues) {
            Add-PreflightError -Code "STOP-CONDITION-MISSING-$stopCondition"
        }
    }

    $evidence = Get-ContractProperty $contractData "evidence"
    $evidenceRootValue = [string](Get-ContractProperty $contractData "evidenceRoot")
    if ($evidenceRootValue -cne [string](Get-ContractProperty $evidence "currentRoot")) {
        Add-PreflightError -Code "EVIDENCE-ROOT-MISMATCH"
    }
    $currentEvidenceRoot = Resolve-RepoFile `
        -RelativeOrAbsolutePath ([string](Get-ContractProperty $evidence "currentRoot")) `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "CURRENT-EVIDENCE-ROOT"
    if ($null -ne $currentEvidenceRoot -and -not (Test-Path -LiteralPath $currentEvidenceRoot -PathType Container)) {
        Add-PreflightError -Code "CURRENT-EVIDENCE-ROOT-MISSING"
    }
    $nextPadRoot = Resolve-RepoFile `
        -RelativeOrAbsolutePath ([string](Get-ContractProperty $evidence "nextPadRoot")) `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "NEXT-PAD-EVIDENCE-ROOT"
    if (
        -not $isPostQaDeferredPhase -and
        $null -ne $nextPadRoot -and
        (Test-Path -LiteralPath $nextPadRoot)
    ) {
        $nextPadChildren = @(Get-ChildItem -LiteralPath $nextPadRoot -Force)
        if ($nextPadChildren.Count -gt 0) {
            Add-PreflightError -Code "NEXT-PAD-EVIDENCE-ROOT-REUSED"
        }
    }
    $deviceLockPath = [string](Get-ContractProperty $evidence "deviceLockPath")
    if (
        $deviceLockPath -cne
        "F:\Develop\codex_work\.planning\cinefin-expansion\device-lock.md"
    ) {
        Add-PreflightError -Code "DEVICE-LOCK-PATH-DRIFT"
    }
    if (-not (Test-Path -LiteralPath $deviceLockPath -PathType Leaf)) {
        Add-PreflightError -Code "DEVICE-LOCK-MISSING"
    }

    $acceptedArtifacts = Get-ContractProperty $contractData "acceptedArtifacts"
    $artifactFacts = [ordered]@{}
    foreach ($artifactName in @("main", "peer")) {
        $artifact = Get-ContractProperty $acceptedArtifacts $artifactName
        $artifactPath = [string](Get-ContractProperty $artifact "path")
        $artifactSha = [string](Get-ContractProperty $artifact "sha256")
        $artifactBytes = Get-ContractProperty $artifact "bytes"
        if ($artifactSha -notmatch "^[0-9a-f]{64}$") {
            Add-PreflightError -Code "ARTIFACT-SHA-INVALID-$artifactName"
        }
        $artifactFullPath = Resolve-RepoFile `
            -RelativeOrAbsolutePath $artifactPath `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "ARTIFACT-$artifactName"
        if ($null -eq $artifactFullPath -or -not (Test-Path -LiteralPath $artifactFullPath -PathType Leaf)) {
            Add-PreflightError -Code "ARTIFACT-MISSING-$artifactName"
            continue
        }
        $artifactFile = Get-Item -LiteralPath $artifactFullPath
        if ($artifactFile.Length -ne [long]$artifactBytes) {
            Add-PreflightError -Code "ARTIFACT-SIZE-MISMATCH-$artifactName"
        }
        $actualArtifactSha = (Get-FileHash -LiteralPath $artifactFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualArtifactSha -cne $artifactSha) {
            Add-PreflightError -Code "ARTIFACT-SHA-MISMATCH-$artifactName"
        }
        $artifactFacts[$artifactName] = [ordered]@{
            bytes = $artifactFile.Length
            sha256 = $actualArtifactSha
        }
    }

    $qaTools = Get-ContractProperty $contractData "qaTools"
    $qaToolsRootPath = [string](Get-ContractProperty $qaTools "root")
    $legacyToolsManifestPath = [string](Get-ContractProperty $qaTools "manifestPath")
    if (
        $qaToolsRootPath -cne
        ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools" -or
        $legacyToolsManifestPath -cne
        ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools.sha256"
    ) {
        Add-PreflightError -Code "QA-TOOLS-PATH-DRIFT"
    }
    $canonicalManifestLines = @(
        Get-ContractProperty $qaTools "canonicalManifestLines"
    )
    $expectedToolsDigest = [string](Get-ContractProperty $qaTools "digestSha256")
    $expectedToolsEntryCount = Get-ContractProperty $qaTools "entryCount"
    $expectedToolsManifestBytes = Get-ContractProperty $qaTools "manifestBytes"
    $expectedCanonicalManifestSha256 = [string](
        Get-ContractProperty $qaTools "canonicalManifestSha256"
    )
    $expectedToolsSortOrder = [string](Get-ContractProperty $qaTools "sortOrder")
    $expectedToolsSelfExcluded = Get-ContractProperty $qaTools "selfExcluded"
    $legacyManifestSha256 = [string](
        Get-ContractProperty $qaTools "legacyManifestSha256"
    )
    $legacyManifestState = [string](
        Get-ContractProperty $qaTools "legacyManifestState"
    )
    if ($expectedToolsDigest -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "TOOLS-DIGEST-INVALID"
    }
    if ($expectedToolsEntryCount -notmatch "^\d+$" -or [int]$expectedToolsEntryCount -lt 1) {
        Add-PreflightError -Code "TOOLS-ENTRY-COUNT-INVALID"
    }
    elseif ([int]$expectedToolsEntryCount -ne 31) {
        Add-PreflightError -Code "TOOLS-ENTRY-COUNT-NOT-31"
    }
    if (
        $expectedToolsManifestBytes -notmatch "^\d+$" -or
        [int]$expectedToolsManifestBytes -lt 1
    ) {
        Add-PreflightError -Code "TOOLS-MANIFEST-BYTES-INVALID"
    }
    elseif ([int]$expectedToolsManifestBytes -ne 2984) {
        Add-PreflightError -Code "TOOLS-MANIFEST-BYTES-NOT-2984"
    }
    if ($expectedCanonicalManifestSha256 -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-SHA-INVALID"
    }
    elseif ($expectedCanonicalManifestSha256 -cne $expectedToolsDigest) {
        Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-SHA-DIGEST-MISMATCH"
    }
    if ($expectedToolsSortOrder -cne "relative path ordinal") {
        Add-PreflightError -Code "TOOLS-SORT-ORDER-INVALID"
    }
    if ($expectedToolsSelfExcluded -ne $true) {
        Add-PreflightError -Code "TOOLS-SELF-EXCLUSION-INVALID"
    }
    if (-not (Test-StringArray -Value $canonicalManifestLines)) {
        Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-INVALID"
    }
    if ($legacyManifestSha256 -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-SHA-INVALID"
    }
    elseif (
        $legacyManifestSha256 -cne
        "d8e98a90b250bafb1bb48d62f315041fd0f67653d8d81c4650aca9fb617d0bfb"
    ) {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-SHA-DRIFT"
    }
    if (
        $legacyManifestState -cne
        "STALE_21_ENTRY_PRE_REPAIR_SNAPSHOT_HISTORY_ONLY"
    ) {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-STATE-INVALID"
    }
    $supersededContractDigest = [string](
        Get-ContractProperty $qaTools "supersededContractDigestSha256"
    )
    $supersededContractDigestState = [string](
        Get-ContractProperty $qaTools "supersededContractDigestState"
    )
    if ($supersededContractDigest -notmatch "^[0-9a-f]{64}$") {
        Add-PreflightError -Code "SUPERSEDED-CONTRACT-DIGEST-INVALID"
    }
    elseif (
        $supersededContractDigest -cne
        "a7e5def25343bb96c15b76398b421115de60f40824698d67a682f3c4b7908901"
    ) {
        Add-PreflightError -Code "SUPERSEDED-CONTRACT-DIGEST-DRIFT"
    }
    if (
        $supersededContractDigestState -cne
        "HISTORICAL_ROUND1_DIGEST_NOT_CURRENT_AUTHORITY"
    ) {
        Add-PreflightError -Code "SUPERSEDED-CONTRACT-DIGEST-STATE-INVALID"
    }
    $repairEvidence = Get-ContractProperty $qaTools "repairEvidence"
    if (
        (Get-ContractProperty $repairEvidence "round1ReviewVerdict") -cne "FAIL" -or
        (Get-ContractProperty $repairEvidence "round1Findings") -cne "1_P1_2_P2" -or
        (Get-ContractProperty $repairEvidence "staticSelfTest") -cne "25/25" -or
        (Get-ContractProperty $repairEvidence "testsBefore") -ne 21 -or
        (Get-ContractProperty $repairEvidence "testsAfter") -ne 25 -or
        (Get-ContractProperty $repairEvidence "regressionsAdded") -ne 4 -or
        (Get-ContractProperty $repairEvidence "independentRound2Review") -cne
        "FAIL_1_P2_REPAIRED" -or
        (Get-ContractProperty $repairEvidence "independentRound3Review") -cne "PASS"
    ) {
        Add-PreflightError -Code "REPAIR-EVIDENCE-SUMMARY-INVALID"
    }
    $closedRound1Findings = @(Get-ContractProperty $repairEvidence "findings")
    if ($closedRound1Findings.Count -ne 3) {
        Add-PreflightError -Code "ROUND1-FINDINGS-NOT-CLOSED"
    }
    else {
        foreach ($closedFinding in $closedRound1Findings) {
            if (
                (Get-ContractProperty $closedFinding "status") -cne
                "CLOSED_BY_ROUND2_REVIEW"
            ) {
                Add-PreflightError -Code "ROUND1-FINDING-STATUS-INVALID"
            }
        }
    }
    $round2Findings = @(Get-ContractProperty $repairEvidence "round2Findings")
    if (
        $round2Findings.Count -ne 1 -or
        (Get-ContractProperty $round2Findings[0] "id") -cne "ROUND2-P2-01" -or
        (Get-ContractProperty $round2Findings[0] "severity") -cne "P2" -or
        (Get-ContractProperty $round2Findings[0] "status") -cne
        "CLOSED_BY_ROUND3_REVIEW_PASS"
    ) {
        Add-PreflightError -Code "ROUND2-P2-EVIDENCE-INVALID"
    }
    $round3ReviewEvidence = Get-ContractProperty `
        $repairEvidence `
        "round3ReviewEvidence"
    if (
        (Get-ContractProperty $round3ReviewEvidence "verdict") -cne "PASS" -or
        (Get-ContractProperty $round3ReviewEvidence "owner") -cne
        "ROUND3_READONLY_INTEGRATION_REVIEW_OWNER" -or
        (Get-ContractProperty $round3ReviewEvidence "gitBoundary") -cne "PASS" -or
        (Get-ContractProperty $round3ReviewEvidence "toolsEntryCount") -ne 31 -or
        (Get-ContractProperty $round3ReviewEvidence "toolsManifestBytes") -ne 2984 -or
        (Get-ContractProperty $round3ReviewEvidence "toolsDigestSha256") -cne
        "69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4" -or
        (Get-ContractProperty $round3ReviewEvidence "runTests") -cne "25/25" -or
        (Get-ContractProperty $round3ReviewEvidence "selfTest") -cne
        "AST_31_UTF8_31_PASS" -or
        (Get-ContractProperty $round3ReviewEvidence "contractTests") -cne "16/16" -or
        (Get-ContractProperty $round3ReviewEvidence "forgedContractCases") -cne
        "15_EXIT_2" -or
        (Get-ContractProperty $round3ReviewEvidence "qaV9DryRun") -cne
        "NO_TOOLS_MANIFEST_HASH_OR_MTIME_CHANGE" -or
        (Get-ContractProperty $round3ReviewEvidence "productP0P3") -cne
        "NOT_ASSESSED" -or
        (Get-ContractProperty $round3ReviewEvidence "k60") -cne "NOT_RUN"
    ) {
        Add-PreflightError -Code "ROUND3-REVIEW-EVIDENCE-INVALID"
    }
    if ((Get-ContractProperty $qaTools "rootForceAddRequired") -ne $false) {
        Add-PreflightError -Code "STALE-ROOT-FORCE-ADD-STATE"
    }
    $rootForceAddHint = [string](
        Get-ContractProperty $qaTools "rootForceAddHint"
    )
    if (-not $rootForceAddHint.Contains(
        "c4a2d2a1",
        [System.StringComparison]::Ordinal
    )) {
        Add-PreflightError -Code "COMMITTED-QA-TOOLS-POINTER-MISSING"
    }
    if (
        (Get-ContractProperty $qaTools "inputDigestFrozen") -ne $true -or
        (Get-ContractProperty $qaTools "refreshRequiredAfterAnyToolChange") -ne $true -or
        (Get-ContractProperty $qaTools "executeQaWithUnrefreshedDigest") -cne "FORBIDDEN"
    ) {
        Add-PreflightError -Code "TOOLS-DIGEST-FREEZE-POLICY-INVALID"
    }

    $qaToolsRootFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $qaToolsRootPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "QA-TOOLS-ROOT"
    if ($null -eq $qaToolsRootFullPath -or -not (Test-Path -LiteralPath $qaToolsRootFullPath -PathType Container)) {
        Add-PreflightError -Code "QA-TOOLS-ROOT-MISSING"
    }

    $legacyToolsManifestFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $legacyToolsManifestPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "LEGACY-TOOLS-MANIFEST"
    if (
        $null -eq $legacyToolsManifestFullPath -or
        -not (Test-Path -LiteralPath $legacyToolsManifestFullPath -PathType Leaf)
    ) {
        Add-PreflightError -Code "LEGACY-TOOLS-MANIFEST-MISSING"
    }
    else {
        $actualCanonicalManifestSha256 = (
            Get-FileHash `
                -LiteralPath $legacyToolsManifestFullPath `
                -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        if ($actualCanonicalManifestSha256 -cne $expectedCanonicalManifestSha256) {
            Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-SHA-MISMATCH"
        }
        $actualCanonicalManifestBytes = (
            Get-Item -LiteralPath $legacyToolsManifestFullPath
        ).Length
        if ($actualCanonicalManifestBytes -ne [int]$expectedToolsManifestBytes) {
            Add-PreflightError -Code "CANONICAL-TOOLS-MANIFEST-BYTES-MISMATCH"
        }
    }

    if (
        $null -ne $qaToolsRootFullPath -and
        (Test-Path -LiteralPath $qaToolsRootFullPath -PathType Container) -and
        (Test-StringArray -Value $canonicalManifestLines)
    ) {
        $manifestRelativePaths = [System.Collections.Generic.List[string]]::new()
        $manifestPathSet = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        $manifestShaByPath = @{}
        $toolsRootPrefix = $qaToolsRootFullPath.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        ) + [System.IO.Path]::DirectorySeparatorChar

        foreach ($manifestLine in $canonicalManifestLines) {
            if ($manifestLine -notmatch "^([0-9a-f]{64})  tools/([^/\\]+(?:/[^/\\]+)*)$") {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-LINE-INVALID"
                continue
            }
            $manifestSha = $Matches[1]
            $manifestRelativePath = $Matches[2]
            $pathSegments = @($manifestRelativePath -split "/")
            if ($pathSegments -contains "." -or $pathSegments -contains "..") {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-PATH-INVALID"
                continue
            }
            if (-not $manifestPathSet.Add($manifestRelativePath)) {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-DUPLICATE"
                continue
            }
            $manifestRelativePaths.Add($manifestRelativePath)
            $manifestShaByPath[$manifestRelativePath] = $manifestSha

            $manifestToolFullPath = [System.IO.Path]::GetFullPath(
                (Join-Path $qaToolsRootFullPath $manifestRelativePath)
            )
            if (
                -not $manifestToolFullPath.StartsWith(
                    $toolsRootPrefix,
                    [System.StringComparison]::OrdinalIgnoreCase
                )
            ) {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-PATH-ESCAPE"
                continue
            }
            if (-not (Test-Path -LiteralPath $manifestToolFullPath -PathType Leaf)) {
                Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-FILE-MISSING"
                continue
            }
            $actualManifestToolSha = (
                Get-FileHash `
                    -LiteralPath $manifestToolFullPath `
                    -Algorithm SHA256
            ).Hash.ToLowerInvariant()
            if ($actualManifestToolSha -cne $manifestSha) {
                Add-PreflightError -Code "TOOLS-FILE-SHA-MISMATCH-$manifestRelativePath"
            }
        }

        $actualRelativePaths = [string[]]$manifestRelativePaths.ToArray()
        $ordinalRelativePaths = [string[]]@($actualRelativePaths)
        [Array]::Sort($ordinalRelativePaths, [System.StringComparer]::Ordinal)
        if (
            ($actualRelativePaths -join "`n") -cne
            ($ordinalRelativePaths -join "`n")
        ) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-NOT-ORDINAL"
        }
        if ($actualRelativePaths.Count -ne [int]$expectedToolsEntryCount) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-ENTRY-COUNT-MISMATCH"
        }

        $canonicalManifestText = ($canonicalManifestLines -join "`n") + "`n"
        $canonicalManifestBytes = [System.Text.UTF8Encoding]::new(
            $false
        ).GetBytes($canonicalManifestText)
        if ($canonicalManifestBytes.Length -ne [int]$expectedToolsManifestBytes) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-BYTES-MISMATCH"
        }
        $canonicalManifestDigest = [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData(
                $canonicalManifestBytes
            )
        ).ToLowerInvariant()
        if ($canonicalManifestDigest -cne $expectedToolsDigest) {
            Add-PreflightError -Code "TOOLS-CANONICAL-MANIFEST-DIGEST-MISMATCH"
        }

        $actualToolsSnapshot = Get-QaToolsDigestSnapshot `
            -ToolsRoot $qaToolsRootFullPath
        $script:toolsDigestValue = $actualToolsSnapshot.digestSha256
        $script:toolsEntryCount = $actualToolsSnapshot.entryCount
        if ($actualToolsSnapshot.digestSha256 -cne $expectedToolsDigest) {
            Add-PreflightError -Code "TOOLS-DIGEST-MISMATCH"
        }
        if ($actualToolsSnapshot.entryCount -ne [int]$expectedToolsEntryCount) {
            Add-PreflightError -Code "TOOLS-DIRECTORY-ENTRY-COUNT-MISMATCH"
        }
        if (-not (Test-OrdinalSetEqual `
            -Actual ([string[]]$actualToolsSnapshot.manifestLines) `
            -Expected ([string[]]$canonicalManifestLines))) {
            Add-PreflightError -Code "TOOLS-DIRECTORY-MANIFEST-MISMATCH"
        }
    }

    $expectedAuditReceiptSchema = @(
        "schema",
        "verdict",
        "auditorRole",
        "auditedSourceCommit",
        "auditedSourceTree",
        "auditedAt",
        "qaPromptSha256",
        "toolsDigest",
        "deviceLockState",
        "deviceLockSha256",
        "qaEvidenceRoot",
        "rejectedScopeHash",
        "memoryIndexSha256",
        "immutableStateSha256",
        "transitionPaths"
    )
    $expectedImmutableStateFields = @(
        "acceptedArtifacts",
        "contractVersion",
        "devicePolicy",
        "evidence",
        "evidenceRoot",
        "firstReadOnlyActions",
        "generatedAt",
        "handoffFlags",
        "inputBaseline",
        "productGates",
        "qaTools",
        "qa_prompt_path",
        "qaPromptPath",
        "readOrder",
        "rejectedScope",
        "roles",
        "schema",
        "stopConditions"
    )
    $expectedPostMutationPointerAllowlist = @(
        "/auditReceipt",
        "/validation/handoffPhase",
        "/validation/activePromptExpectedRoleId",
        "/validation/qaPromptRequiredStatus",
        "/qaRoundState/status",
        "/qaRoundState/preHandoffAudit",
        "/qaRoundState/padQaPromptDispatch",
        "/activePrompt",
        "/next_prompt_path",
        "/nextPromptPath",
        "/prompts/toolRepair/status",
        "/prompts/toolRepair/dispatchable",
        "/prompts/toolRepair/retiredReason",
        "/prompts/padQa/status",
        "/prompts/padQa/dispatchable",
        "/prompts/padQa/retiredReason",
        "/requiredGates/lifecycle/4/status",
        "/requiredGates/lifecycle/5/status"
    )
    $expectedPostQaMutationPointerAllowlist = @(
        $expectedPostMutationPointerAllowlist +
        @(
            "/qaRoundState/coreStepsExecuted",
            "/qaRoundState/padTenMinuteCoreRun",
            "/qaRoundState/k60Registration",
            "/qaRoundState/k60CoreRun",
            "/qaRoundState/cleanup",
            "/qaRoundState/padLockActiveCount"
        )
    )
    $expectedPostAuditTransitionPaths = @(
        "docs/TASKS.md",
        "docs/V2_PLAN.md",
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
    )
    $expectedPostQaSettlementTransitionPaths = @(
        "AGENTS.md",
        "docs/DECISIONS.md",
        "docs/PROJECT_PLAN.md",
        "docs/TASKS.md",
        "docs/V2_PLAN.md",
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json",
        "tools/v2-handoff/Invoke-HandoffPreflight.ps1",
        "tools/v2-handoff/Test-HandoffPreflight.ps1"
    )
    $expectedPostQaSettlementTransitionPrefixes = @(
        ".planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/",
        ".planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v9/"
    )
    $expectedPreAuditDirtyPaths = @(
        "AGENTS.md",
        "docs/PROJECT_RULES.md",
        "docs/PROJECT_PLAN.md",
        "docs/V2_PLAN.md",
        "docs/TASKS.md",
        "docs/LESSONS.md",
        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json",
        "tools/v2-handoff/Invoke-HandoffPreflight.ps1",
        "tools/v2-handoff/Test-HandoffPreflight.ps1"
    )
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $validation "auditReceiptSchema") `
        -Expected $expectedAuditReceiptSchema)) {
        Add-PreflightError -Code "AUDIT-RECEIPT-SCHEMA-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $validation "immutableStateFields") `
        -Expected $expectedImmutableStateFields)) {
        Add-PreflightError -Code "IMMUTABLE-STATE-FIELD-LIST-DRIFT"
    }
    $actualActiveTokenPointers = @(
        Get-ContractProperty $validation "activeRequiredTokens"
    )
    if (
        $actualActiveTokenPointers.Count -ne 1 -or
        $actualActiveTokenPointers[0] -cne $expectedActiveTokenPointers[0]
    ) {
        Add-PreflightError -Code "ACTIVE-REQUIRED-TOKEN-POINTER-DRIFT"
    }
    if (-not (Test-RequiredTextAnchorPointers `
        -Value (Get-ContractProperty $validation "requiredTextAnchors"))) {
        Add-PreflightError -Code "REQUIRED-TEXT-ANCHOR-POINTER-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $validation "postMutationPointerAllowlist") `
        -Expected $expectedPostMutationPointerAllowlist)) {
        Add-PreflightError -Code "POST-MUTATION-POINTER-ALLOWLIST-DRIFT"
    }
    if (-not (Test-OrdinalStringArrayEqual `
        -Actual (Get-ContractProperty $validation "postAuditTransitionPaths") `
        -Expected $expectedPostAuditTransitionPaths)) {
        Add-PreflightError -Code "POST-AUDIT-TRANSITION-ALLOWLIST-DRIFT"
    }
    if (-not (Test-OrdinalSetEqual `
        -Actual ([string[]]@(
            Get-ContractProperty $validation "preAuditAllowedDirtyPaths"
        )) `
        -Expected $expectedPreAuditDirtyPaths)) {
        Add-PreflightError -Code "PRE-AUDIT-DIRTY-ALLOWLIST-DRIFT"
    }
    $expectedAlgorithmBindings = [ordered]@{
        qaPromptSha256Algorithm = "SHA256 UTF8(LF; prompts.padQa.textLines joined by LF; final LF)"
        rejectedScopeHashAlgorithm = "SHA256 UTF8(LF; rejectedScope serialized as compact JSON in property order; final LF)"
        memoryIndexSha256Algorithm = "SHA256 raw MEMORY.md bytes"
        deviceLockStateAlgorithm = "RELEASED_ACTIVE_0 only when current-occupancy headings contain no ACTIVE lock"
        toolsDigestAlgorithm = "SHA256 UTF8(LF; '<file-sha256>  tools/<relative>' lines; relative path ordinal; final LF; tools.sha256 self-excluded)"
        immutableStateSha256Algorithm = "SHA256 UTF8(LF; selected source-contract fields canonicalized with ordinal-sorted object keys, arrays in order, compact JSON; final LF)"
    }
    foreach ($algorithmName in $expectedAlgorithmBindings.Keys) {
        if (
            (Get-ContractProperty $validation $algorithmName) -cne
            $expectedAlgorithmBindings[$algorithmName]
        ) {
            Add-PreflightError -Code "VALIDATION-ALGORITHM-DRIFT-$algorithmName"
        }
    }
    $expectedMemoryIndexPath = "F:\Develop\codex_work\Documents\Codex\engramory-memory\MEMORY.md"
    $memoryIndexPath = [string](
        Get-ContractProperty $validation "memoryIndexPath"
    )
    if ($memoryIndexPath -cne $expectedMemoryIndexPath) {
        Add-PreflightError -Code "MEMORY-INDEX-PATH-DRIFT"
    }

    if (-not $isPostAuditPhase) {
        $currentPreAuditState = Assert-PreAuditState `
            -Contract $contractData `
            -Mode "CURRENT" `
            -ReferenceContract $contractData `
            -RequiredTopLevelNames $requiredTopLevelNames `
            -ExpectedRoleIds $expectedRoleIds `
            -ExpectedImmutableStateFields $expectedImmutableStateFields `
            -ExpectedActiveTokenPointers $expectedActiveTokenPointers `
            -ExpectedRequiredTextAnchorPointers $expectedRequiredTextAnchorPointers `
            -ExpectedPostMutationPointerAllowlist $expectedPostMutationPointerAllowlist `
            -ExpectedPostAuditTransitionPaths $expectedPostAuditTransitionPaths `
            -ExpectedAuditReceiptSchema $expectedAuditReceiptSchema `
            -SourceContractRelativePath "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
        if ($null -ne $currentPreAuditState) {
            $script:preAuditSubstateValue = [string]$currentPreAuditState.substate
            $preAuditSubstate = [string]$currentPreAuditState.substate
            $preReviewGateStatus = [string]$currentPreAuditState.reviewGateStatus
            $preExpectedRoundStatus = [string]$currentPreAuditState.expectedStatus
        }
    }

    if ($isPostAuditPhase) {
        $script:auditReceiptState = "REQUIRED"
        if (-not (Test-ExactObjectProperties `
            -ObjectValue $auditReceipt `
            -ExpectedProperties $expectedAuditReceiptSchema)) {
            Add-PreflightError -Code "AUDIT-RECEIPT-SCHEMA-INVALID"
        }
        else {
            $receiptSchema = [string](
                Get-ContractProperty $auditReceipt "schema"
            )
            if ($receiptSchema -cne "aurorama.handoff-audit-receipt.v1") {
                Add-PreflightError -Code "AUDIT-RECEIPT-IDENTITY-INVALID"
            }
            if ((Get-ContractProperty $auditReceipt "verdict") -cne "PASS") {
                Add-PreflightError -Code "AUDIT-RECEIPT-VERDICT-NOT-PASS"
            }
            if (
                (Get-ContractProperty $auditReceipt "auditorRole") -cne
                "PRE_HANDOFF_AUDIT_OWNER"
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-AUDITOR-DRIFT"
            }

            $auditedSourceCommit = [string](
                Get-ContractProperty $auditReceipt "auditedSourceCommit"
            )
            $auditedSourceTree = [string](
                Get-ContractProperty $auditReceipt "auditedSourceTree"
            )
            if ($auditedSourceCommit -notmatch "^[0-9a-f]{40}$") {
                Add-PreflightError -Code "AUDIT-RECEIPT-SOURCE-COMMIT-INVALID"
            }
            else {
                & git -C $repositoryRoot cat-file -e "$auditedSourceCommit^{commit}" 2>$null
                if ($LASTEXITCODE -ne 0) {
                    Add-PreflightError -Code "AUDIT-RECEIPT-SOURCE-COMMIT-MISSING"
                }
                else {
                    $postCurrentHead = ([string](& git -C $repositoryRoot rev-parse HEAD 2>$null)).Trim()
                    & git -C $repositoryRoot merge-base --is-ancestor $auditedSourceCommit $postCurrentHead 2>$null
                    if ($LASTEXITCODE -ne 0) {
                        Add-PreflightError -Code "AUDIT-RECEIPT-SOURCE-NOT-ANCESTOR"
                    }
                    $actualSourceTree = (
                        [string](& git -C $repositoryRoot rev-parse "$auditedSourceCommit^{tree}" 2>$null)
                    ).Trim()
                    if ($LASTEXITCODE -ne 0) {
                        Add-PreflightError -Code "AUDIT-RECEIPT-SOURCE-TREE-READ-FAILED"
                    }
                    elseif ($auditedSourceTree -cne $actualSourceTree) {
                        Add-PreflightError -Code "AUDIT-RECEIPT-SOURCE-TREE-MISMATCH"
                    }

                    $sourceContractRelativePath =
                        "tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json"
                    $sourceContractText = Read-GitCommitPathStrictUtf8LfFile `
                        -RepositoryRootPath $repositoryRoot `
                        -CommitId $auditedSourceCommit `
                        -RelativePath $sourceContractRelativePath `
                        -ErrorPrefix "AUDIT-SOURCE-CONTRACT"
                    if ($null -ne $sourceContractText) {
                        try {
                            $sourceContractData = $sourceContractText |
                                ConvertFrom-Json -DateKind String
                        }
                        catch {
                            Add-PreflightError -Code "AUDIT-SOURCE-CONTRACT-JSON-INVALID"
                        }
                    }
                }
            }

            $auditedAtText = [string](
                Get-ContractProperty $auditReceipt "auditedAt"
            )
            $auditedAtValue = [DateTimeOffset]::MinValue
            if (
                $auditedAtText -notmatch "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})$" -or
                -not [DateTimeOffset]::TryParse(
                    $auditedAtText,
                    [System.Globalization.CultureInfo]::InvariantCulture,
                    [System.Globalization.DateTimeStyles]::None,
                    [ref]$auditedAtValue
                )
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-TIMESTAMP-INVALID"
            }

            $receiptQaPromptSha256 = [string](
                Get-ContractProperty $auditReceipt "qaPromptSha256"
            )
            $actualQaPromptSha256 = if ($null -ne $padQaPromptText) {
                Get-Utf8LfSha256 -TextValue $padQaPromptText
            }
            else {
                "unknown"
            }
            if (
                $receiptQaPromptSha256 -notmatch "^[0-9a-f]{64}$" -or
                $receiptQaPromptSha256 -cne $actualQaPromptSha256
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-QA-PROMPT-HASH-DRIFT"
            }

            $receiptToolsDigest = [string](
                Get-ContractProperty $auditReceipt "toolsDigest"
            )
            if (
                $receiptToolsDigest -notmatch "^[0-9a-f]{64}$" -or
                $receiptToolsDigest -cne $actualToolsSnapshot.digestSha256 -or
                $receiptToolsDigest -cne $expectedToolsDigest
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-TOOLS-DIGEST-DRIFT"
            }

            $deviceLockSnapshot = Get-DeviceLockSnapshot `
                -DeviceLockPath $deviceLockPath
            if ($null -ne $deviceLockSnapshot) {
                $receiptDeviceLockState = [string](
                    Get-ContractProperty $auditReceipt "deviceLockState"
                )
                $receiptDeviceLockSha256 = [string](
                    Get-ContractProperty $auditReceipt "deviceLockSha256"
                )
                if (
                    $receiptDeviceLockState -cne "RELEASED_ACTIVE_0" -or
                    $deviceLockSnapshot.state -cne $receiptDeviceLockState
                ) {
                    Add-PreflightError -Code "AUDIT-RECEIPT-DEVICE-LOCK-STATE-DRIFT"
                }
                if (
                    $receiptDeviceLockSha256 -notmatch "^[0-9a-f]{64}$" -or
                    (
                        $isPostQaDeferredPhase -and
                        $receiptDeviceLockSha256 -cne
                        $expectedHistoricalAuditDeviceLockSha256
                    ) -or
                    (
                        -not $isPostQaDeferredPhase -and
                        $receiptDeviceLockSha256 -cne $deviceLockSnapshot.sha256
                    )
                ) {
                    Add-PreflightError -Code "AUDIT-RECEIPT-DEVICE-LOCK-HASH-DRIFT"
                }
            }

            $receiptQaEvidenceRoot = [string](
                Get-ContractProperty $auditReceipt "qaEvidenceRoot"
            )
            if (
                $receiptQaEvidenceRoot -cne
                [string](Get-ContractProperty $evidence "nextPadRoot")
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-QA-EVIDENCE-ROOT-DRIFT"
            }
            if ($isPostQaDeferredPhase) {
                $qaV9Root = Resolve-RepoFile `
                    -RelativeOrAbsolutePath $receiptQaEvidenceRoot `
                    -RepositoryRootPath $repositoryRoot `
                    -ErrorCode "POST-QA-EVIDENCE-ROOT"
                if (
                    $null -eq $qaV9Root -or
                    -not (Test-Path -LiteralPath $qaV9Root -PathType Container) -or
                    @(Get-ChildItem -LiteralPath $qaV9Root -Force).Count -eq 0
                ) {
                    Add-PreflightError -Code "POST-QA-EVIDENCE-ROOT-MISSING"
                }
                $qaV9ReportRelativePath = $receiptQaEvidenceRoot.TrimEnd("/") + "/report.json"
                $qaV9ReportPath = Resolve-RepoFile `
                    -RelativeOrAbsolutePath $qaV9ReportRelativePath `
                    -RepositoryRootPath $repositoryRoot `
                    -ErrorCode "POST-QA-REPORT"
                if (
                    $null -eq $qaV9ReportPath -or
                    -not (Test-Path -LiteralPath $qaV9ReportPath -PathType Leaf)
                ) {
                    Add-PreflightError -Code "POST-QA-REPORT-MISSING"
                }
                else {
                    $qaV9ReportText = Read-StrictUtf8LfFile `
                        -AbsolutePath $qaV9ReportPath `
                        -ErrorPrefix "POST-QA-REPORT"
                    if ($null -ne $qaV9ReportText) {
                        try {
                            $qaV9Report = $qaV9ReportText |
                                ConvertFrom-Json -DateKind String
                            if (
                                (Get-ContractProperty $qaV9Report "schema") -cne
                                "aurorama.kokoro-qa-v9.report.v1" -or
                                (Get-ContractProperty $qaV9Report "status") -cne
                                "BLOCKED_TOOL_NONCONVERGENCE" -or
                                (Get-ContractProperty $qaV9Report "verdict") -cne
                                "BLOCKED" -or
                                (Get-ContractProperty $qaV9Report "productVerdict") -cne
                                "NOT_ASSESSED"
                            ) {
                                Add-PreflightError -Code "POST-QA-REPORT-VERDICT-INVALID"
                            }
                            $qaV9Object = Get-ContractProperty $qaV9Report "object"
                            if (
                                (Get-ContractProperty $qaV9Object "qaEvidenceRoot") -cne
                                $receiptQaEvidenceRoot
                            ) {
                                Add-PreflightError -Code "POST-QA-REPORT-ROOT-DRIFT"
                            }
                            foreach ($artifactName in @("main", "peer")) {
                                $contractArtifact = Get-ContractProperty `
                                    $acceptedArtifacts `
                                    $artifactName
                                $reportArtifact = Get-ContractProperty `
                                    $qaV9Object `
                                    $artifactName
                                if (
                                    (Get-ContractProperty $reportArtifact "sha256") -cne
                                    (Get-ContractProperty $contractArtifact "sha256") -or
                                    (Get-ContractProperty $reportArtifact "bytes") -ne
                                    (Get-ContractProperty $contractArtifact "bytes")
                                ) {
                                    Add-PreflightError -Code (
                                        "POST-QA-REPORT-ARTIFACT-MISMATCH-" + $artifactName
                                    )
                                }
                            }
                            $qaV9Tools = Get-ContractProperty $qaV9Object "tools"
                            if (
                                (Get-ContractProperty $qaV9Tools "aggregateSha256") -cne
                                $expectedToolsDigest
                            ) {
                                Add-PreflightError -Code "POST-QA-REPORT-TOOLS-MISMATCH"
                            }
                            $qaV9Device = Get-ContractProperty $qaV9Object "device"
                            if (
                                (Get-ContractProperty $qaV9Device "serial") -cne
                                "43af8627"
                            ) {
                                Add-PreflightError -Code "POST-QA-REPORT-PAD-SERIAL-MISMATCH"
                            }
                            $qaV9ProductFindings = Get-ContractProperty `
                                $qaV9Report `
                                "productFindings"
                            foreach ($severity in @("P0", "P1", "P2", "P3")) {
                                if (
                                    (Get-ContractProperty `
                                        $qaV9ProductFindings `
                                        $severity) -cne "NOT_ASSESSED"
                                ) {
                                    Add-PreflightError -Code (
                                        "POST-QA-REPORT-PRODUCT-ASSESSED-" + $severity
                                    )
                                }
                            }
                            $qaV9Executed = Get-ContractProperty $qaV9Report "executed"
                            $qaV9Zf = Get-ContractProperty $qaV9Executed "zf001"
                            if (
                                (Get-ContractProperty $qaV9Executed "coreStepsAttempted") -ne 1 -or
                                (Get-ContractProperty $qaV9Zf "status") -cne "PARTIAL" -or
                                (Get-ContractProperty $qaV9Zf "realAudioTrackStart") -ne $true -or
                                (Get-ContractProperty $qaV9Zf "audioTrackCompletion") -cne
                                "NOT_RUN" -or
                                (Get-ContractProperty $qaV9Executed "zm010") -cne
                                "NOT_RUN" -or
                                (Get-ContractProperty $qaV9Executed "deviceWindowMinutesRequested") -ne
                                10 -or
                                (Get-ContractProperty $qaV9Executed "deviceWindowExecuted") -ne
                                $false -or
                                (Get-ContractProperty $qaV9Executed "cleanup") -cne
                                "PASS_MANUAL_CROSSCHECK"
                            ) {
                                Add-PreflightError -Code "POST-QA-REPORT-PARTIAL-SCOPE-INVALID"
                            }
                            $qaV9Cleanup = Get-ContractProperty `
                                $qaV9Report `
                                "cleanupAndRestore"
                            $qaV9CleanupLock = Get-ContractProperty `
                                $qaV9Cleanup `
                                "deviceLock"
                            $qaV9K60 = Get-ContractProperty $qaV9Cleanup "k60"
                            if (
                                (Get-ContractProperty $qaV9Cleanup "status") -cne
                                "PASS_MANUAL_CROSSCHECK" -or
                                (Get-ContractProperty $qaV9CleanupLock "state") -cne
                                "RELEASED_ACTIVE_0" -or
                                (Get-ContractProperty $qaV9CleanupLock "activeCount") -ne 0 -or
                                (Get-ContractProperty $qaV9K60 "registered") -ne $false -or
                                (Get-ContractProperty $qaV9K60 "targetedCommands") -ne 0
                            ) {
                                Add-PreflightError -Code "POST-QA-REPORT-CLEANUP-LOCK-INVALID"
                            }
                        }
                        catch {
                            Add-PreflightError -Code "POST-QA-REPORT-JSON-INVALID"
                        }
                    }
                }
            }

            $receiptRejectedScopeHash = [string](
                Get-ContractProperty $auditReceipt "rejectedScopeHash"
            )
            $actualRejectedScopeHash = Get-CompactJsonSha256 -Value $rejectedScope
            if (
                $receiptRejectedScopeHash -notmatch "^[0-9a-f]{64}$" -or
                $receiptRejectedScopeHash -cne $actualRejectedScopeHash
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-REJECTED-SCOPE-DRIFT"
            }

            $receiptMemoryIndexSha256 = [string](
                Get-ContractProperty $auditReceipt "memoryIndexSha256"
            )
            if (-not (Test-Path -LiteralPath $memoryIndexPath -PathType Leaf)) {
                Add-PreflightError -Code "MEMORY-INDEX-MISSING"
            }
            else {
                $actualMemoryIndexSha256 = Get-BytesSha256 `
                    -Bytes ([System.IO.File]::ReadAllBytes($memoryIndexPath))
                if (
                    $receiptMemoryIndexSha256 -notmatch "^[0-9a-f]{64}$" -or
                    (
                        $isPostQaDeferredPhase -and
                        $receiptMemoryIndexSha256 -cne
                        $expectedHistoricalAuditMemoryIndexSha256
                    ) -or
                    (
                        -not $isPostQaDeferredPhase -and
                        $receiptMemoryIndexSha256 -cne $actualMemoryIndexSha256
                    )
                ) {
                    Add-PreflightError -Code "AUDIT-RECEIPT-MEMORY-INDEX-DRIFT"
                }
            }

            if ($null -eq $sourceContractData) {
                Add-PreflightError -Code "AUDIT-SOURCE-CONTRACT-UNAVAILABLE"
            }
            else {
                $sourcePreAuditState = Assert-PreAuditState `
                    -Contract $sourceContractData `
                    -Mode "SOURCE" `
                    -ReferenceContract $contractData `
                    -RequiredTopLevelNames $requiredTopLevelNames `
                    -ExpectedRoleIds $expectedRoleIds `
                    -ExpectedImmutableStateFields $expectedImmutableStateFields `
                    -ExpectedActiveTokenPointers $expectedActiveTokenPointers `
                    -ExpectedRequiredTextAnchorPointers $expectedRequiredTextAnchorPointers `
                    -ExpectedPostMutationPointerAllowlist $expectedPostMutationPointerAllowlist `
                    -ExpectedPostAuditTransitionPaths $expectedPostAuditTransitionPaths `
                    -ExpectedAuditReceiptSchema $expectedAuditReceiptSchema `
                    -SourceContractRelativePath $sourceContractRelativePath
                if ($null -eq $sourcePreAuditState) {
                    Add-PreflightError -Code "AUDIT-SOURCE-PRE-STATE-UNAVAILABLE"
                }
                if (
                    (Get-ContractProperty $sourceContractData "contractVersion") -cne
                    "HANDOFF_BOOTSTRAP_V2" -or
                    (Get-ContractProperty $sourceContractData "schema") -cne
                    "aurorama.handoff-bootstrap.v2"
                ) {
                    Add-PreflightError -Code "AUDIT-SOURCE-CONTRACT-IDENTITY-INVALID"
                }
                $sourceValidation = Get-ContractProperty $sourceContractData "validation"
                if (
                    (Get-ContractProperty $sourceValidation "handoffPhase") -cne
                    "PRE_HANDOFF_AUDIT" -or
                    $null -ne (Get-ContractProperty $sourceContractData "auditReceipt")
                ) {
                    Add-PreflightError -Code "AUDIT-SOURCE-CONTRACT-PHASE-INVALID"
                }
                if (-not (Test-OrdinalStringArrayEqual `
                    -Actual (Get-ContractProperty $sourceValidation "immutableStateFields") `
                    -Expected $expectedImmutableStateFields)) {
                    Add-PreflightError -Code "AUDIT-SOURCE-IMMUTABLE-FIELDS-DRIFT"
                }
                $sourceActiveTokenPointers = @(
                    Get-ContractProperty $sourceValidation "activeRequiredTokens"
                )
                if (
                    $sourceActiveTokenPointers.Count -ne 1 -or
                    $sourceActiveTokenPointers[0] -cne $expectedActiveTokenPointers[0]
                ) {
                    Add-PreflightError -Code "AUDIT-SOURCE-ACTIVE-TOKEN-POINTER-DRIFT"
                }
                if (-not (Test-RequiredTextAnchorPointers `
                    -Value (Get-ContractProperty $sourceValidation "requiredTextAnchors"))) {
                    Add-PreflightError -Code "AUDIT-SOURCE-ANCHOR-POINTER-DRIFT"
                }
                if (-not (Test-OrdinalStringArrayEqual `
                    -Actual (Get-ContractProperty $sourceValidation "postMutationPointerAllowlist") `
                    -Expected $expectedPostMutationPointerAllowlist)) {
                    Add-PreflightError -Code "AUDIT-SOURCE-MUTATION-ALLOWLIST-DRIFT"
                }
                if (
                    (Get-ContractProperty $sourceValidation "immutableStateSha256Algorithm") -cne
                    "SHA256 UTF8(LF; selected source-contract fields canonicalized with ordinal-sorted object keys, arrays in order, compact JSON; final LF)"
                ) {
                    Add-PreflightError -Code "AUDIT-SOURCE-IMMUTABLE-ALGORITHM-DRIFT"
                }

                $sourceImmutableProjection = Get-ImmutableContractProjection `
                    -Contract $sourceContractData `
                    -Fields $expectedImmutableStateFields
                $currentImmutableProjection = Get-ImmutableContractProjection `
                    -Contract $contractData `
                    -Fields $expectedImmutableStateFields
                $actualSourceImmutableStateSha256 = Get-Utf8LfSha256 `
                    -TextValue (Get-CanonicalJsonText -Value $sourceImmutableProjection)
                $actualCurrentImmutableStateSha256 = Get-Utf8LfSha256 `
                    -TextValue (Get-CanonicalJsonText -Value $currentImmutableProjection)
                $receiptImmutableStateSha256 = [string](
                    Get-ContractProperty $auditReceipt "immutableStateSha256"
                )
                if (
                    $receiptImmutableStateSha256 -notmatch "^[0-9a-f]{64}$" -or
                    $receiptImmutableStateSha256 -cne $actualSourceImmutableStateSha256 -or
                    $receiptImmutableStateSha256 -cne $actualCurrentImmutableStateSha256
                ) {
                    Add-PreflightError -Code "AUDIT-RECEIPT-IMMUTABLE-STATE-HASH-DRIFT"
                }

                $sourceTopLevelNames = [string[]]@(
                    $sourceContractData.PSObject.Properties |
                        ForEach-Object { $_.Name }
                )
                if (
                    $sourceTopLevelNames.Count -ne $requiredTopLevelNames.Count -or
                    -not (Test-OrdinalSetEqual `
                        -Actual $sourceTopLevelNames `
                        -Expected ([string[]]$requiredTopLevelNames))
                ) {
                    Add-PreflightError -Code "POST-MUTATION-TOP-LEVEL-SET-MISMATCH"
                }
                $mutationPointers =
                    [System.Collections.Generic.List[string]]::new()
                Add-JsonMutationDiff `
                    -SourceValue $sourceContractData `
                    -CurrentValue $contractData `
                    -Pointer "" `
                    -Mutations $mutationPointers
                $activeMutationAllowlist = if ($isPostQaDeferredPhase) {
                    $expectedPostQaMutationPointerAllowlist
                }
                else {
                    $expectedPostMutationPointerAllowlist
                }
                foreach ($mutationPointer in @($mutationPointers)) {
                    if (-not (Test-JsonPointerAllowed `
                        -Pointer $mutationPointer `
                        -AllowedPatterns $activeMutationAllowlist)) {
                        Add-PreflightError -Code (
                            "POST-MUTATION-POINTER-NOT-ALLOWED:" + $mutationPointer
                        )
                    }
                }
            }

            $receiptTransitionPathsValue = Get-ContractProperty `
                $auditReceipt `
                "transitionPaths"
            if (
                -not (Test-StringArray -Value $receiptTransitionPathsValue) -or
                -not (Test-OrdinalStringArrayEqual `
                    -Actual $receiptTransitionPathsValue `
                    -Expected $expectedPostAuditTransitionPaths)
            ) {
                Add-PreflightError -Code "AUDIT-RECEIPT-TRANSITION-PATH-DRIFT"
            }
            else {
                $script:transitionPathCount = @($receiptTransitionPathsValue).Count
            }
        }
    }
    else {
        $script:auditReceiptState = "INACTIVE"
    }

    $reportPath = [string](Get-ContractProperty $evidence "currentReportPath")
    $reportFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $reportPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "QA-REPORT"
    if ($null -ne $reportFullPath -and (Test-Path -LiteralPath $reportFullPath -PathType Leaf)) {
        $reportText = Read-StrictUtf8LfFile `
            -AbsolutePath $reportFullPath `
            -ErrorPrefix "QA-REPORT"
        try {
            $reportData = $reportText | ConvertFrom-Json -DateKind String
            if ((Get-ContractProperty $reportData "status") -cne "BLOCKED_TOOL_NONCONVERGENCE") {
                Add-PreflightError -Code "QA-REPORT-STATUS-MISMATCH"
            }
            if (
                (Get-ContractProperty (Get-ContractProperty (Get-ContractProperty $reportData "findings") "product") "P0") -cne
                "NOT_ASSESSED"
            ) {
                Add-PreflightError -Code "QA-REPORT-PRODUCT-MISMATCH"
            }
            $reportObject = Get-ContractProperty $reportData "object"
            $reportMain = Get-ContractProperty $reportObject "hostMain"
            $reportPeer = Get-ContractProperty $reportObject "hostPeer"
            foreach ($artifactName in @("main", "peer")) {
                $contractArtifact = Get-ContractProperty $acceptedArtifacts $artifactName
                $reportArtifact = if ($artifactName -eq "main") { $reportMain } else { $reportPeer }
                if (
                    (Get-ContractProperty $reportArtifact "sha256") -cne
                    (Get-ContractProperty $contractArtifact "sha256") -or
                    (Get-ContractProperty $reportArtifact "bytes") -ne
                    (Get-ContractProperty $contractArtifact "bytes")
                ) {
                    Add-PreflightError -Code "QA-REPORT-ARTIFACT-MISMATCH-$artifactName"
                }
            }
            $reportTools = Get-ContractProperty $reportObject "tools"
            if ((Get-ContractProperty $reportTools "aggregateSha256") -cne $legacyManifestSha256) {
                Add-PreflightError -Code "QA-REPORT-HISTORICAL-TOOLS-MISMATCH"
            }
            $reportDevice = Get-ContractProperty $reportObject "device"
            if ((Get-ContractProperty $reportDevice "serial") -cne "43af8627") {
                Add-PreflightError -Code "QA-REPORT-PAD-SERIAL-MISMATCH"
            }
            $reportCleanup = Get-ContractProperty $reportData "cleanup"
            if ((Get-ContractProperty $reportCleanup "status") -cne "PASS_CLEANUP_AND_RESTORE") {
                Add-PreflightError -Code "QA-REPORT-CLEANUP-MISMATCH"
            }
            $reportDeviceLock = Get-ContractProperty $reportData "deviceLock"
            if ((Get-ContractProperty $reportDeviceLock "activeCountAfterClosure") -ne 0) {
                Add-PreflightError -Code "QA-REPORT-DEVICE-LOCK-ACTIVE"
            }
        }
        catch {
            Add-PreflightError -Code "QA-REPORT-JSON-INVALID"
        }
    }

    $validation = Get-ContractProperty $contractData "validation"
    $expectedActivePromptRole = if ($isPostQaDeferredPhase) {
        "NONE"
    }
    elseif ($isPostAuditPhase) {
        "PAD_QA_OWNER"
    }
    elseif ($preAuditSubstate -ceq "PRE_REVIEW_PASS_AWAITING_AUDIT") {
        "PRE_HANDOFF_AUDIT_OWNER"
    }
    else {
        "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER"
    }
    $expectedQaPromptRequiredStatus = if ($isPostQaDeferredPhase) {
        "RETIRED_RUNTIME_QA_DEFERRED_TO_G2"
    }
    elseif ($isPostAuditPhase) {
        "ACTIVE"
    }
    else {
        "BLOCKED_PENDING_PRE_HANDOFF_AUDIT"
    }
    $activePromptExpectedRoleId = [string](
        Get-ContractProperty $validation "activePromptExpectedRoleId"
    )
    $activePromptRoleMatches = if ($isPostQaDeferredPhase) {
        $activePromptName -ceq "none"
    }
    else {
        (Get-ContractProperty `
            (Get-ContractProperty $promptsObject $activePromptName) `
            "roleId") -ceq $activePromptExpectedRoleId
    }
    if (
        $activePromptExpectedRoleId -cne
        $expectedActivePromptRole -or
        -not $activePromptRoleMatches
    ) {
        Add-PreflightError -Code "ACTIVE-PROMPT-EXPECTED-ROLE-INVALID"
    }
    $qaPromptRequiredStatus = [string](
        Get-ContractProperty $validation "qaPromptRequiredStatus"
    )
    if (
        $qaPromptRequiredStatus -cne
        $expectedQaPromptRequiredStatus -or
        (Get-ContractProperty $padQaPrompt "status") -cne
        $qaPromptRequiredStatus
    ) {
        Add-PreflightError -Code "QA-PROMPT-REQUIRED-STATUS-INVALID"
    }
    $activeDocumentPath = [string](Get-ContractProperty $validation "activeDocument")
    $activeDocumentFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $activeDocumentPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "ACTIVE-DOCUMENT"
    if ($null -ne $activeDocumentFullPath) {
        $activeDocumentText = Read-StrictUtf8LfFile `
            -AbsolutePath $activeDocumentFullPath `
            -ErrorPrefix "ACTIVE-DOCUMENT"
        $activeDocumentLines = @($activeDocumentText -split "`n")
        $activeHeadingPrefix = [string](Get-ContractProperty $validation "activeHeadingPrefix")
        $activeIndexes = @(
            for ($lineIndex = 0; $lineIndex -lt $activeDocumentLines.Count; $lineIndex++) {
                if ($activeDocumentLines[$lineIndex].StartsWith(
                    $activeHeadingPrefix,
                    [System.StringComparison]::Ordinal
                )) {
                    $lineIndex
                }
            }
        )
        if ($activeIndexes.Count -ne 1) {
            Add-PreflightError -Code "ACTIVE-SECTION-COUNT-NOT-ONE"
        }
        else {
            $activeStart = $activeIndexes[0]
            $activeEnd = $activeDocumentLines.Count
            for ($lineIndex = $activeStart + 1; $lineIndex -lt $activeDocumentLines.Count; $lineIndex++) {
                if ($activeDocumentLines[$lineIndex].StartsWith(
                    "### HISTORY",
                    [System.StringComparison]::Ordinal
                )) {
                    $activeEnd = $lineIndex
                    break
                }
            }
            $activeSectionText = (
                $activeDocumentLines[$activeStart..($activeEnd - 1)] -join "`n"
            )
            $activeRequiredTokens = if ($isPostQaDeferredPhase) {
                $postQaDeferredActiveRequiredTokens
            }
            elseif ($isPostAuditPhase) {
                $postActiveRequiredTokens
            }
            elseif ($preActiveRequiredTokensByState.ContainsKey($preAuditSubstate)) {
                [string[]]@($preActiveRequiredTokensByState[$preAuditSubstate])
            }
            else {
                [string[]]@()
            }
            Assert-Tokens `
                -TextValue $activeSectionText `
                -Tokens $activeRequiredTokens `
                -ErrorPrefix "ACTIVE-SECTION"
            $mandatoryActiveTokens = if ($isPostQaDeferredPhase) {
                @(
                    "validation.handoffPhase=POST_QA_RUNTIME_DEFERRED",
                    "RUNTIME_QA_DEFERRED_TO_G2",
                    "G1_STATIC_CLOSURE_IN_PROGRESS",
                    "G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS",
                    "V2-D209",
                    "G2_RUNTIME_CONTRACT_OWNER",
                    "QA_V9_BLOCKED_TOOL_NONCONVERGENCE",
                    "PRODUCT_P0_P3_NOT_ASSESSED",
                    "K60_LOCKED",
                    "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
                    "PRE_HANDOFF_AUDIT_PASS=PASS",
                    "QA_DISPATCHABLE=DEFERRED_TO_G2",
                    "V2-D208",
                    "auditReceipt"
                )
            }
            elseif ($isPostAuditPhase) {
                @(
                    "POST_HANDOFF_AUDIT_READY",
                    "validation.handoffPhase=POST_HANDOFF_AUDIT_READY",
                    "INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS",
                    "PRE_HANDOFF_AUDIT_PASS=PASS",
                    "QA_DISPATCHABLE=PASS",
                    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
                    "auditReceipt"
                )
            }
            else {
                @(
                    "validation.handoffPhase=PRE_HANDOFF_AUDIT",
                    ("INDEPENDENT_INTEGRATION_REVIEW_PASS=" + $preReviewGateStatus),
                    "PRE_HANDOFF_AUDIT_PASS=BLOCKED",
                    "QA_DISPATCHABLE=BLOCKED",
                    $preExpectedRoundStatus,
                    "ROUND9_READONLY_INTEGRATION_REVIEW_OWNER",
                    "auditReceipt"
                )
            }
            Assert-Tokens `
                -TextValue $activeSectionText `
                -Tokens $mandatoryActiveTokens `
                -ErrorPrefix "MANDATORY-ACTIVE"
        }
    }

    $tasksDocumentPath = [string](Get-ContractProperty $validation "tasksDocument")
    $tasksInProgressHeading = [string](
        Get-ContractProperty $validation "tasksInProgressHeading"
    )
    $tasksInProgressExpectedCount = Get-ContractProperty `
        $validation `
        "tasksInProgressExpectedCount"
    if (
        $tasksInProgressExpectedCount -notmatch "^\d+$" -or
        [int]$tasksInProgressExpectedCount -lt 1
    ) {
        Add-PreflightError -Code "TASKS-IN-PROGRESS-EXPECTED-COUNT-INVALID"
    }
    $tasksDocumentFullPath = Resolve-RepoFile `
        -RelativeOrAbsolutePath $tasksDocumentPath `
        -RepositoryRootPath $repositoryRoot `
        -ErrorCode "TASKS-DOCUMENT"
    if ($null -ne $tasksDocumentFullPath) {
        $tasksDocumentText = Read-StrictUtf8LfFile `
            -AbsolutePath $tasksDocumentFullPath `
            -ErrorPrefix "TASKS-DOCUMENT"
        $tasksDocumentLines = @($tasksDocumentText -split "`n")
        $tasksHeadingIndexes = @(
            for ($lineIndex = 0; $lineIndex -lt $tasksDocumentLines.Count; $lineIndex++) {
                if (
                    $tasksDocumentLines[$lineIndex] -ceq
                    $tasksInProgressHeading
                ) {
                    $lineIndex
                }
            }
        )
        if ($tasksHeadingIndexes.Count -ne 1) {
            Add-PreflightError -Code "TASKS-IN-PROGRESS-HEADING-COUNT-NOT-ONE"
        }
        else {
            $tasksStart = $tasksHeadingIndexes[0] + 1
            $tasksEnd = $tasksDocumentLines.Count
            for ($lineIndex = $tasksStart; $lineIndex -lt $tasksDocumentLines.Count; $lineIndex++) {
                if ($tasksDocumentLines[$lineIndex].StartsWith(
                    "## ",
                    [System.StringComparison]::Ordinal
                )) {
                    $tasksEnd = $lineIndex
                    break
                }
            }
            $inProgressRows = @(
                for ($lineIndex = $tasksStart; $lineIndex -lt $tasksEnd; $lineIndex++) {
                    if ($tasksDocumentLines[$lineIndex] -match '^\| `V2-[^`]+`') {
                        $tasksDocumentLines[$lineIndex]
                    }
                }
            )
            $script:tasksInProgressCount = $inProgressRows.Count
            if ($inProgressRows.Count -ne [int]$tasksInProgressExpectedCount) {
                Add-PreflightError -Code "TASKS-IN-PROGRESS-COUNT-MISMATCH"
            }
        }
    }

    $requiredTextAnchors = @(
        Get-ContractProperty $validation "requiredTextAnchors"
    )
    foreach ($anchor in $requiredTextAnchors) {
        $anchorId = [string](Get-ContractProperty $anchor "id")
        $anchorPath = [string](Get-ContractProperty $anchor "path")
        $anchorFullPath = Resolve-RepoFile `
            -RelativeOrAbsolutePath $anchorPath `
            -RepositoryRootPath $repositoryRoot `
            -ErrorCode "ANCHOR"
        if ($null -eq $anchorFullPath) {
            continue
        }
        $anchorText = Read-StrictUtf8LfFile `
            -AbsolutePath $anchorFullPath `
            -ErrorPrefix "ANCHOR-$anchorPath"
        $anchorTokens = if ($isPostQaDeferredPhase) {
            @($postQaDeferredAnchorTokenSets[$anchorId])
        }
        elseif ($isPostAuditPhase) {
            @($postAnchorTokenSets[$anchorId])
        }
        else {
            @($preAnchorTokenSetsByState[$preAuditSubstate][$anchorId])
        }
        Assert-Tokens `
            -TextValue $anchorText `
            -Tokens $anchorTokens `
            -ErrorPrefix "ANCHOR-$anchorPath"
        $forbiddenTokens = @($anchorForbiddenTokenSets[$anchorId])
        foreach ($forbiddenToken in $forbiddenTokens) {
            if (
                (Test-NonEmptyString $forbiddenToken) -and
                $anchorText.Contains($forbiddenToken, [System.StringComparison]::Ordinal)
            ) {
                Add-PreflightError -Code "STALE-TOKEN-PRESENT-$anchorPath"
            }
        }
    }

    if ($null -ne $inputCommit -and $null -ne $inputBranch) {
        $branchOutput = & git -C $repositoryRoot rev-parse --abbrev-ref HEAD 2>$null
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "GIT-BRANCH-READ-FAILED"
        }
        else {
            $script:branchValue = ([string]$branchOutput).Trim()
            if ($script:branchValue -cne $inputBranch) {
                Add-PreflightError -Code "GIT-BRANCH-MISMATCH"
            }
        }

        $headOutput = & git -C $repositoryRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "GIT-HEAD-READ-FAILED"
        }
        else {
            $script:headValue = ([string]$headOutput).Trim()
        }

        & git -C $repositoryRoot merge-base --is-ancestor $inputCommit $script:headValue 2>$null
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "INPUT-BASELINE-NOT-ANCESTOR"
        }

        $statusOutput = @(& git -C $repositoryRoot status --porcelain 2>$null)
        if ($LASTEXITCODE -ne 0) {
            Add-PreflightError -Code "GIT-STATUS-READ-FAILED"
        }
        else {
            $script:dirtyCount = $statusOutput.Count
        }

        if ($isPostAuditPhase) {
            if ($auditedSourceCommit -notmatch "^[0-9a-f]{40}$") {
                Add-PreflightError -Code "AUDIT-TRANSITION-SOURCE-INVALID"
            }
            else {
                $postAuditChangedPaths = @(
                    Get-ChangedRepoPaths `
                        -RepositoryRootPath $repositoryRoot `
                        -BaseRevision $auditedSourceCommit `
                        -ErrorPrefix "POST-AUDIT"
                )
                $script:currentTransitionPathCount = $postAuditChangedPaths.Count
                if ($isPostQaDeferredPhase) {
                    foreach ($changedPath in $postAuditChangedPaths) {
                        if (-not (Test-ChangedPathAllowed `
                            -Path $changedPath `
                            -ExactPaths $expectedPostQaSettlementTransitionPaths `
                            -AllowedPrefixes $expectedPostQaSettlementTransitionPrefixes)) {
                            Add-PreflightError -Code "POST-QA-TRANSITION-PATH-OUTSIDE-ALLOWLIST"
                        }
                    }
                }
                elseif (-not (Test-OrdinalStringArrayEqual `
                    -Actual ([string[]]$postAuditChangedPaths) `
                    -Expected ([string[]]$expectedPostAuditTransitionPaths))) {
                    Add-PreflightError -Code "POST-AUDIT-TRANSITION-PATH-SET-MISMATCH"
                }
            }
        }
        else {
            $preAuditChangedPaths = @(
                Get-ChangedRepoPaths `
                    -RepositoryRootPath $repositoryRoot `
                    -BaseRevision "HEAD" `
                    -ErrorPrefix "PRE-AUDIT"
            )
            foreach ($changedPath in $preAuditChangedPaths) {
                if ($changedPath -cnotin $expectedPreAuditDirtyPaths) {
                    Add-PreflightError -Code "PRE-AUDIT-DIRTY-PATH-OUTSIDE-ALLOWLIST"
                }
            }
        }
    }
}
catch {
    Add-PreflightError -Code ("PREFLIGHT-EXCEPTION-" + $_.Exception.GetType().Name)
    if ($env:AURORAMA_PREFLIGHT_DEBUG -ceq "1") {
        Write-Output ("DEBUG_EXCEPTION " + $_.Exception.Message)
        Write-Output ("DEBUG_STACK " + $_.ScriptStackTrace)
    }
}

if ($validationErrors.Count -gt 0) {
    $errorCodes = @(
        $validationErrors |
            Select-Object -Unique |
            Select-Object -First 8
    ) -join ","
    Write-Output (
        "FAIL_CLOSED HANDOFF_BOOTSTRAP_V2 errors={0} codes={1}" -f
        $validationErrors.Count,
        $errorCodes
    )
    exit 2
}

Write-Output (
    "PASS HANDOFF_BOOTSTRAP_V2 phase={0} receipt={1} receiptTransitions={2} currentTransitions={13} branch={3} head={4} dirty={5} input={6} roles={7} tasks={8} gates={9} order=Pad->K60 window=10 apk=MATCH tools=MATCH manifest=MATCH({10}/{10}) digest={11} docs=MATCH lock=RELEASED pre={12}" -f
    $script:handoffPhaseValue,
    $script:auditReceiptState,
    $script:transitionPathCount,
    $script:branchValue,
    $script:headValue,
    $script:dirtyCount,
    $inputCommit.Substring(0, 12),
    $script:roleCount,
    $script:tasksInProgressCount,
    $script:gateCount,
    $script:toolsEntryCount,
    $script:toolsDigestValue,
    $script:preAuditSubstateValue,
    $script:currentTransitionPathCount
)

if ($EmitPrompt) {
    if ($isPostQaDeferredPhase) {
        Write-Output "NEXT_PROMPT_PATH=docs/V2_PLAN.md#ACTIVE"
        Write-Output "NEXT_PROMPT_BEGIN"
        Write-Output "R2_WIP_REPAIR_R3_IN_PROGRESS；qaDispatchable=false；RUNTIME_QA_DEFERRED_TO_G2；G1_STATIC_CLOSURE_IN_PROGRESS；G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS；PAD_RUNTIME_ATTEMPT_SCOPE_V2；K60_GATED_LOCKED；V2-D210；当前合同没有可派发 QA prompt；activePrompt=none。首次 Pad QA 因路径绑定停止，qa-pad-v1 的 NOT_STARTED blocker、cleanup 快照与自持锁保持原字节；新 attempt 仅使用 qa-pad-v2 / qa-k60-v2，并绑定外部权威锁 RELEASED_ACTIVE_0、registration/session 与 finalizer released lock。R2 WIP 未验收；R3 只完成实现侧修复，不声称 ready。下一步由 root 决定 fresh scoped review；review 第一条只读动作必须是 git status --short，随后 git diff --stat。"
        Write-Output "NEXT_PROMPT_END"
    }
    else {
        $selectedPromptObject = Get-ContractProperty $promptsObject $selectedPromptName
        $selectedPromptText = Get-PromptText `
            -PromptValue $selectedPromptObject `
            -ErrorPrefix "EMIT-$selectedPromptName"
        $selectedPromptPath = if ($selectedPromptName -eq "padQa") {
            $qaPromptPath
        }
        else {
            $nextPromptPath
        }
        Write-Output "NEXT_PROMPT_PATH=$selectedPromptPath"
        Write-Output "NEXT_PROMPT_BEGIN"
        Write-Output $selectedPromptText
        Write-Output "NEXT_PROMPT_END"
    }
}
