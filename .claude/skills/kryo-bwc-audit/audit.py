#!/usr/bin/env python3
"""Kryo backward-compatibility audit.

Verifies that every class serialized through evitaDB's `SerialVersionBasedSerializer`
whose format changed since the previous released version has its `serialVersionUID`
bumped exactly once, and that a matching backward-compatible (BWC) reader is registered
in *every* Kryo configurer that registers the class.

Mechanism recap (see SKILL.md): `SerialVersionBasedSerializer` stamps the target class's
own `serialVersionUID` on write; on read, a stamp != current UID with no BWC reader for
that stamp throws `StoredVersionNotSupportedException`. Kryo's `DefaultClassResolver`
registers by class with last-write-wins semantics, so when two configurers register the
same class into one composed kryo, the later registration's reader set is the effective
one — a reader present in only one of them can be silently overridden.

Usage:
    python3 audit.py [--prev <ref>] [--fork <ref>] [--repo <path>]

    --prev  previous released git ref (default: auto-detect newest release_* branch)
    --fork  dev fork point that current work branched from — catches "orphaned dev UID"
            cases where a class churned in dev after the release (default: merge-base of
            HEAD and origin/dev, falling back to dev)
    --repo  repository root (default: current working directory)

Exit code is 0 when no problems are found, 1 otherwise. The report lists every registered
class with its UID at each ref and a verdict; the FINDINGS section enumerates only the
problems.
"""
import argparse
import os
import re
import subprocess
import sys

REG_RE = re.compile(r"new SerialVersionBasedSerializer<>\(new (\w+)\(\),\s*(\w+)\.class\)")
BWC_RE = re.compile(r"addBackwardCompatibleSerializer\((-?\d+)L,\s*new (\w+)\(\)")
UID_RE = re.compile(r"serialVersionUID\s*=\s*(-?\d+)L")


def sh(args, repo):
    return subprocess.run(args, cwd=repo, capture_output=True, text=True)


def detect_prev_release(repo):
    out = sh(["git", "branch", "-a", "--list", "*release_*"], repo).stdout
    branches = []
    for line in out.splitlines():
        name = line.strip().lstrip("* ").replace("remotes/origin/", "")
        m = re.search(r"release_(\d{4})-(\d+)", name)
        if m:
            branches.append(((int(m.group(1)), int(m.group(2))), name))
    if not branches:
        return None
    branches.sort()
    return branches[-1][1]


def detect_fork(repo):
    for base in ("origin/dev", "dev"):
        r = sh(["git", "merge-base", "HEAD", base], repo)
        if r.returncode == 0 and r.stdout.strip():
            return r.stdout.strip()
    return None


def discover_configurers(repo):
    out = sh(["git", "grep", "-l", "SerialVersionBasedSerializer", "--", "*.java"], repo).stdout
    files = []
    for f in out.splitlines():
        if "/test/" in f or f.endswith("SerialVersionBasedSerializer.java"):
            continue
        # only files that actually *register* something (contain the constructor call)
        try:
            with open(os.path.join(repo, f)) as fh:
                if "new SerialVersionBasedSerializer<>(" in fh.read():
                    files.append(f)
        except OSError:
            pass
    return sorted(files)


def all_java_paths(repo):
    return set(sh(["git", "ls-files", "*.java"], repo).stdout.splitlines())


def build_path_index(repo, paths):
    idx = {}
    for p in paths:
        idx.setdefault(os.path.basename(p)[:-5], []).append(p)
    return idx


def parse_imports(cf, repo):
    """Return (explicit {simpleName: fqn}, wildcard [package]) for a configurer file."""
    explicit, wildcards = {}, []
    with open(os.path.join(repo, cf)) as f:
        for line in f:
            m = re.match(r"\s*import\s+([\w.]+)\.(\w+)\s*;", line)
            if m:
                explicit[m.group(2)] = m.group(1) + "." + m.group(2)
                continue
            w = re.match(r"\s*import\s+([\w.]+)\.\*\s*;", line)
            if w:
                wildcards.append(w.group(1))
    return explicit, wildcards


def fqn_to_path(fqn, paths):
    suffix = "/" + fqn.replace(".", "/") + ".java"
    for p in paths:
        if p.endswith(suffix) and "/src/main/java/" in p:
            return p
    return None


def resolve_path(cls, explicit, wildcards, paths, idx):
    """Resolve a registered simple class name to its source path, disambiguating via the
    registering configurer's imports (explicit first, then wildcard packages), falling back
    to a best-effort index lookup. Returns (path|None, confident:bool)."""
    if cls in explicit:
        p = fqn_to_path(explicit[cls], paths)
        if p:
            return (p, True)
    for wp in wildcards:
        p = fqn_to_path(wp + "." + cls, paths)
        if p:
            return (p, True)
    cands = idx.get(cls, [])
    prod = [p for p in cands if "/src/main/java/" in p]
    api = [p for p in prod if "evita_api/" in p]
    chosen = (api or prod or cands or [None])[0]
    # confident only when the name is unambiguous
    return (chosen, len(prod) == 1)


def uid_at(path, ref, repo):
    """(uid:str|None, exists:bool). ref=None means working tree."""
    if path is None:
        return (None, False)
    if ref is None:
        try:
            with open(os.path.join(repo, path)) as f:
                txt = f.read()
        except FileNotFoundError:
            return (None, False)
    else:
        r = sh(["git", "show", f"{ref}:{path}"], repo)
        if r.returncode != 0:
            return (None, False)
        txt = r.stdout
    m = UID_RE.search(txt)
    return (m.group(1), True) if m else (None, True)


def serializer_changed(serializer_cls, compare_ref, explicit, wildcards, paths, idx, repo):
    """True if the current serializer body differs from the fork/prev ref (i.e. this branch
    changed it). Dated snapshot serializers (_YYYY_M) are never 'current'."""
    if re.search(r"_20\d{2}_\d+$", serializer_cls):
        return False
    path, _ = resolve_path(serializer_cls, explicit, wildcards, paths, idx)
    if path is None:
        return False
    r = sh(["git", "diff", "--quiet", compare_ref, "--", path], repo)
    return r.returncode != 0  # non-zero = differs


def parse_registrations(configurers, repo):
    regs = []
    for cf in configurers:
        explicit, wildcards = parse_imports(cf, repo)
        with open(os.path.join(repo, cf)) as f:
            lines = f.readlines()
        cur = None
        for ln in lines:
            m = REG_RE.search(ln)
            if m:
                cur = {"configurer": os.path.basename(cf), "cls": m.group(2),
                       "serializer": m.group(1), "bwc": [],
                       "explicit": explicit, "wildcards": wildcards}
                regs.append(cur)
            b = BWC_RE.search(ln)
            if b and cur is not None:
                cur["bwc"].append(b.group(1))
    return regs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prev")
    ap.add_argument("--fork")
    ap.add_argument("--repo", default=os.getcwd())
    args = ap.parse_args()
    repo = os.path.abspath(args.repo)

    prev = args.prev or detect_prev_release(repo)
    fork = args.fork or detect_fork(repo)
    if not prev:
        print("ERROR: could not detect a previous release ref; pass --prev", file=sys.stderr)
        return 2
    print(f"repo : {repo}")
    print(f"prev : {prev}   (previously released)")
    print(f"fork : {fork}   (dev fork point / orphan anchor)")

    paths = all_java_paths(repo)
    idx = build_path_index(repo, paths)
    configurers = discover_configurers(repo)
    print(f"configurers scanned: {', '.join(os.path.basename(c) for c in configurers)}\n")
    regs = parse_registrations(configurers, repo)

    # class -> list of registrations (for the multi-configurer override check)
    by_class = {}
    for r in regs:
        by_class.setdefault(r["cls"], []).append(r)

    findings = []
    notes = []
    header = f"{'CLASS':<52} {'configurer':<34} {'prev':>21} {'fork':>21} {'cur':>21}  verdict"
    print(header)
    print("-" * len(header))
    compare_ref = fork or prev
    for reg in regs:
        cls = reg["cls"]
        path, confident = resolve_path(cls, reg["explicit"], reg["wildcards"], paths, idx)
        prev_uid, prev_ex = uid_at(path, prev, repo)
        fork_uid, fork_ex = uid_at(path, fork, repo) if fork else (None, False)
        cur_uid, cur_ex = uid_at(path, None, repo)
        keys = set(reg["bwc"])

        required = set()
        if prev_ex and prev_uid and prev_uid != cur_uid:
            required.add(prev_uid)
        if fork_ex and fork_uid and fork_uid != cur_uid:
            required.add(fork_uid)
        missing = required - keys

        ser_changed = serializer_changed(
            reg["serializer"], compare_ref, reg["explicit"], reg["wildcards"], paths, idx, repo)
        silent = ser_changed and cur_uid is not None and cur_uid == prev_uid  # limb 2

        # A registered class the tool cannot pin to a serialVersionUID is a *blocking* condition:
        # a safety gate must fail loud when it cannot verify, never pass silently (a mis-resolved or
        # UID-less class could hide a real orphan). Only a stable UID-less class (unchanged
        # serializer) is downgraded to a note for a human to eyeball.
        if path is None:
            # Unresolvable = nested class (no standalone file) or a name the resolver missed. Block only
            # when the serializer changed on this branch (a real, unverifiable change signal); a stable
            # one is a note. Nested-class UIDs live in the enclosing file and are not extracted reliably.
            verdict = ("*** UNVERIFIED: cannot resolve source (serializer changed on this branch)"
                       if ser_changed
                       else "unresolved source (nested/UID-less; serializer unchanged — verify manually)")
        elif not confident and cur_uid is None:
            verdict = "*** UNVERIFIED: ambiguous class name, no serialVersionUID found"
        elif cur_uid is None:
            verdict = ("*** UNVERIFIED: no explicit serialVersionUID and serializer changed"
                       if ser_changed
                       else "no explicit serialVersionUID (unchanged — verify not format-versioned)")
        elif not prev_ex and not fork_ex:
            verdict = "NEW (no persisted history)"
        elif missing:
            verdict = "*** MISSING READER: " + ",".join(sorted(missing))
        elif silent:
            verdict = "*** SERIALIZER CHANGED WITHOUT UID BUMP"
        else:
            verdict = "OK"

        # '***' verdicts are problems (fail the audit); a stable UID-less class is a non-blocking note.
        if verdict.startswith("***"):
            findings.append((cls, reg["configurer"], verdict, prev_uid, fork_uid, cur_uid, sorted(keys)))
        elif verdict.startswith("no explicit") or verdict.startswith("unresolved source"):
            notes.append((cls, reg["configurer"], verdict, prev_uid, fork_uid, cur_uid, sorted(keys)))

        def f(x):
            return x if x else "-"
        print(f"{cls:<52} {reg['configurer']:<34} {f(prev_uid):>21} {f(fork_uid):>21} {f(cur_uid):>21}  {verdict}")

    # multi-configurer coverage: a bumped class registered in >1 configurer must carry the
    # required readers in EVERY registration (Kryo last-write-wins can override a lone reader).
    for cls, rlist in by_class.items():
        if len(rlist) < 2:
            continue
        path, _ = resolve_path(cls, rlist[0]["explicit"], rlist[0]["wildcards"], paths, idx)
        prev_uid, prev_ex = uid_at(path, prev, repo)
        cur_uid, _ = uid_at(path, None, repo)
        if not (prev_ex and prev_uid and cur_uid and prev_uid != cur_uid):
            continue
        for reg in rlist:
            if prev_uid not in set(reg["bwc"]):
                msg = (cls, reg["configurer"],
                       f"*** MULTI-CONFIGURER: reader for prev UID {prev_uid} absent here but class is bumped",
                       prev_uid, None, cur_uid, sorted(set(reg["bwc"])))
                if not any(m[0] == cls and m[1] == reg["configurer"] for m in findings):
                    findings.append(msg)

    print("\n\n===== FINDINGS =====")
    if not findings:
        print("none — every registered class that changed since the previous release is bumped "
              "exactly once and carries the required BWC readers in every configurer.")
    for cls, cf, verdict, p, fk, c, keys in findings:
        print(f"\n{cls}  [{cf}]")
        print(f"  prev={p} fork={fk} cur={c}")
        print(f"  registered BWC keys here: {keys}")
        print(f"  {verdict}")

    if notes:
        print("\n----- NOTES (no serialVersionUID found; unchanged — verify not format-versioned) -----")
        for cls, cf, _verdict, _p, _fk, _c, _keys in notes:
            print(f"  {cls}  [{cf}]")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
