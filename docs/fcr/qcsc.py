# Reference for golden tests G6/G7; cited by spec 2026-11-18-finite-capacity-regions-design.md section 8.
# Writes qcsc-model.json and qcsc-members.txt to the current directory.
"""QCSC-shaped model (2 classes = 2 streams, 12 single-server queues + 2 fork-joins, all exponential)
and its exact cap-1 reference: multi-class M/G/1 FCFS, S = one job's sojourn through an empty network."""
import json
LAM = {"ps": 0.15, "sp": 0.10}; P0 = 0.3           # P0 = prob of quantum-first tandem
R = {"cpu": 4.0, "qpu": 1.5, "gpu": 2.5}
FJ = {"fj_pp": (1.0, 2.0), "fj_sp": (1.2, 3.0)}     # (Q branch rate, G branch rate)
def exp(rate): return {c: {"distribution": {"type": "exponential", "rate": rate}} for c in LAM}
nodes = [{"name": "src", "type": "source", "arrivals": {c: {"distribution": {"type": "exponential", "rate": l}} for c, l in LAM.items()}}]
def q(name, kind): nodes.append({"name": name, "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": None, "service": exp(R[kind])})
for n in ["cpu_init_ps", "cpu_term_ps", "cpu_init_sp", "cpu_term_sp"]: q(n, "cpu")
for n in ["qpu_psq", "qpu_psg", "qpu_ssq", "qpu_ssg"]: q(n, "qpu")
for n in ["gpu_psq", "gpu_psg", "gpu_ssq", "gpu_ssg"]: q(n, "gpu")
for n, (a, b) in FJ.items():
    nodes.append({"name": n, "type": "fork-join", "join": "all", "branches": [{"service": exp(a)}, {"service": exp(b)}]})
nodes.append({"name": "snk", "type": "sink"})
def e(f, t, p=None): return {"from": f, "to": t, "probability": p}
def seq(pre, before, after):   # quantum-first (P0) vs classical-first tandem
    return [e(before, f"qpu_{pre}q", P0), e(before, f"gpu_{pre}g", 1 - P0),
            e(f"qpu_{pre}q", f"gpu_{pre}q"), e(f"gpu_{pre}q", after),
            e(f"gpu_{pre}g", f"qpu_{pre}g"), e(f"qpu_{pre}g", after)]
routing = {
  "ps": [e("src", "cpu_init_ps"), e("cpu_init_ps", "fj_pp")] + seq("ps", "fj_pp", "cpu_term_ps") + [e("cpu_term_ps", "snk")],
  "sp": [e("src", "cpu_init_sp")] + seq("ss", "cpu_init_sp", "fj_sp") + [e("fj_sp", "cpu_term_sp"), e("cpu_term_sp", "snk")]}
model = {"name": "qcsc-shaped", "classes": [{"name": c, "type": "open"} for c in LAM], "nodes": nodes, "routing": routing}
json.dump(model, open("qcsc-model.json", "w"), indent=1)
members = [n["name"] for n in nodes if n["type"] in ("queue", "fork-join")]
open("qcsc-members.txt", "w").write(",".join(members))
# --- exact cap-1 reference: S per class = independent stages; moments via (mean, var)
def ex(r): return (1 / r, 1 / r**2)
def mx(a, b):   # max of two independent exponentials
    m = 1/a + 1/b - 1/(a+b); m2 = 2/a**2 + 2/b**2 - 2/(a+b)**2; return (m, m2 - m*m)
def total(stages): return (sum(s[0] for s in stages), sum(s[1] for s in stages))
def mix(parts):    # [(w, (mean, var))] -> (E, E2)
    E = sum(w*m for w, (m, v) in parts); E2 = sum(w*(v + m*m) for w, (m, v) in parts); return E, E2
tandem = total([ex(R["qpu"]), ex(R["gpu"])])        # same stages either order
S = {"ps": mix([(1, total([ex(R["cpu"]), mx(*FJ["fj_pp"]), tandem, ex(R["cpu"])]))]),
     "sp": mix([(1, total([ex(R["cpu"]), tandem, mx(*FJ["fj_sp"]), ex(R["cpu"])]))])}
lam = sum(LAM.values())
ES = sum(LAM[c]*S[c][0] for c in LAM)/lam; ES2 = sum(LAM[c]*S[c][1] for c in LAM)/lam
rho = lam*ES; Wq = lam*ES2/(2*(1-rho))
print(f"rho={rho:.5f} E[S]={ES:.5f} E[S2]={ES2:.5f} Wq={Wq:.5f}")
for c in LAM: print(f"  class {c}: E[S]={S[c][0]:.5f}  T={Wq + S[c][0]:.5f}")
print(f"  N_total={lam*(Wq+ES):.5f}  N_inside={rho:.5f}")
for n, (a, b) in FJ.items(): print(f"  {n}: fork-join response (alone) = {mx(a, b)[0]:.5f}")
