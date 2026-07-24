# Platform logic alignment

- Post-climb pose tracking has no timeout while reliable pose remains visible.
- Two consecutive post-climb pose misses still return to initial motion search.
- The normal-finish timestamp uses the stored finish reference directly; no artificial +0.1 second adjustment is applied.
- The five-second timeout applies only to pose loss during an active climb.
