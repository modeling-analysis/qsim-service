# Reference for golden tests G4/G5; cited by spec 2026-11-18-finite-capacity-regions-design.md section 8.1.
"""Exact CTMC for a FCR of capacity K=2 around a 2-branch fork-join (FCFS branches, rates mu0, mu1),
Poisson(lam) arrivals, wait (unbounded FCFS entry queue, truncated at NMAX) or drop."""
import sys
lam, mu0, mu1, mode, NMAX = float(sys.argv[1]), 1.0, 1.0, sys.argv[2], int(sys.argv[3])
K = int(sys.argv[4]) if len(sys.argv) > 4 else 2
inside = [(k, a, b) for k in range(K + 1) for a in range(k + 1) for b in range(k + 1) if min(a, b) == 0]
states = [(0,) + s for s in inside] + [(n,) + s for n in range(1, NMAX + 1 if mode == 'wait' else 1) for s in inside if s[0] == K]
idx = {s: i for i, s in enumerate(states)}
def settle(n, k, a, b):
    while a >= 1 and b >= 1: k, a, b = k - 1, a - 1, b - 1        # head job has both tasks done -> leaves
    while n > 0 and k < K: n, k = n - 1, k + 1                      # admit from entry queue
    return (n, k, a, b)
out = [[] for _ in states]
for s in states:
    n, k, a, b = s
    if k < K: out[idx[s]].append((idx[(n, k + 1, a, b)], lam))
    elif mode == 'wait' and n < NMAX: out[idx[s]].append((idx[(n + 1, k, a, b)], lam))
    if a < k: out[idx[s]].append((idx[settle(n, k, a + 1, b)], mu0))
    if b < k: out[idx[s]].append((idx[settle(n, k, a, b + 1)], mu1))
inn = [[] for _ in states]; rate = [0.0] * len(states)
for i, lst in enumerate(out):
    for j, r in lst: inn[j].append((i, r)); rate[i] += r
p = [1.0 / len(states)] * len(states)
for it in range(200000):
    diff = 0.0
    for j in range(len(states)):
        v = sum(p[i] * r for i, r in inn[j]) / rate[j]; diff = max(diff, abs(v - p[j])); p[j] = v
    t = sum(p); p = [x / t for x in p]
    if diff < 1e-15: break
L = sum(pi * (s[0] + s[1]) for pi, s in zip(p, states)); Lin = sum(pi * s[1] for pi, s in zip(p, states))
pfull = sum(pi for pi, s in zip(p, states) if s[1] == K)
X = lam * (1 - pfull) if mode == 'drop' else lam
tail = sum(pi for pi, s in zip(p, states) if s[0] > NMAX - 20)
print(f"mode={mode} lam={lam} iters={it} tail={tail:.2e}  T={L/X:.5f} N={L:.5f} Ninside={Lin:.5f} "
      f"Tinside={Lin/X:.5f} X={X:.5f} dropRate={lam - X:.5f} Pfull={pfull:.5f}")
