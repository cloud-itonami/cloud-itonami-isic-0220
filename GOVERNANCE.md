# Governance

`cloud-itonami-isic-0220` is an OSS open-business blueprint for community logging operations coordination.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a felling/skidding-equipment action the governor refuses is never dispatched to hardware.
- the Logging Coordination Governor remains independent of the advisor.
- hard policy violations (equipment-control bypass, harvest-cut-plan finalization, record-suppression, unauthorized disclosure) cannot be overridden by human approval.
- every schedule, sign-off, record and disclose path is auditable.
- sensitive operating and personal data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing felling/skidding-equipment-control or record policy checks
- mishandling sensitive data
- misrepresenting certification status
- failing to respond to security or safety incidents
