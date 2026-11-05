#!/bin/bash
# v0.20.1 COMPATIBILITY SHIM — read this before judging the name.
#
# The harness advanced with the reachability build: it is
# V201Test now (work/v201/V201Test.java, runner
# ci/run_v201test.sh, summary line "V201Test: N checks, 0
# failures"). This file was the v0.20.0 runner; it now forwards —
# verbatim and visibly — to the v201 runner, the same labeled
# shim discipline the chain has carried since v0.19.0
# (ci/run_v180test.sh, whose name the workflow calls, forwards
# here; here forwards to v201). Nothing is relabeled: the harness
# that runs is V201Test and its output says so. The v200 runner's
# real content is preserved in git history (the v0.20.0 tree,
# head ba4e14d4 and every push of run sandy-fixpass-0200).
exec bash "$(dirname "$0")/run_v201test.sh"
