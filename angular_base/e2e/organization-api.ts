import { RecordedCall } from './gateway-stub';

/** The tenant these tests run in, and what every page of the app asks for at start. */
export const T = '/organizations/tenant-a';

export interface Fake {
  modules: { LEAVE: boolean; LUNCH_ROULETTE: boolean };
  timezone: string;
  requests: any[];
  allowance: number;
  round: any;
  formed: any | null;
  enrolled: Set<string>;
  config: { day: string; cutoff: string; groupSize: number };
}

export function newFake(overrides: Partial<Fake> = {}): Fake {
  return {
    modules: { LEAVE: true, LUNCH_ROULETTE: true },
    timezone: 'Europe/Bucharest',
    requests: [],
    allowance: 21,
    round: { id: 'rd1', weekStart: '2026-12-07', deadlineAt: '2026-12-10T09:00:00Z', status: 'OPEN', cancelReason: null },
    formed: null,
    enrolled: new Set<string>(),
    config: { day: 'THURSDAY', cutoff: '11:00', groupSize: 2 },
    ...overrides
  };
}

/**
 * A small organization-service in memory: module settings, leave requests with a balance that follows them, and the
 * lunch round. Pages of different users share one {@code Fake}, so a request made by one is seen by the other.
 * {@code userOf} reads the user of the call from its token.
 */
export function organizationApi(fake: Fake) {
  const view = (userId: string) => ({
    ...fake.round,
    participantCount: fake.enrolled.size + (fake.round.id ? 0 : 0),
    enrolled: fake.enrolled.has(userId)
  });
  const userOf = (call: RecordedCall) => {
    const payload = call.authorization?.split('.')[1];
    return payload ? JSON.parse(Buffer.from(payload, 'base64url').toString()).sub : 'unknown';
  };
  const balance = (userId: string) => {
    const mine = fake.requests.filter(r => r.userId === userId);
    const days = (s: string) => mine.filter(r => r.status === s).reduce((sum, r) => sum + r.days, 0);
    return { userId, year: 2026, allocated: fake.allowance, approved: days('APPROVED'), pending: days('PENDING'), available: fake.allowance - days('APPROVED') - days('PENDING') };
  };
  return (call: RecordedCall) => {
    const user = userOf(call);
    const path = call.path;
    if (path === `${T}/modules` && call.method === 'GET') {
      return { body: { modules: fake.modules, timezone: fake.timezone } };
    }
    const toggle = path.match(new RegExp(`^${T}/modules/([A-Z_]+)$`));
    if (toggle && call.method === 'PUT') {
      (fake.modules as any)[toggle[1]] = call.body.enabled;
      return { body: { modules: fake.modules, timezone: fake.timezone } };
    }
    if (path === `${T}/theme`) {
      return { body: { paletteId: 'default', palette: null, logoUrl: null } };
    }
    if (path === '/notifications/in-app') {
      return { body: { items: [], page: 0, size: 20, totalItems: 0, unreadCount: 0 } };
    }
    // ---- leave ----
    if (path === `${T}/leave/balance`) {
      return { body: balance(user) };
    }
    if (path === `${T}/leave/calendar`) {
      return { body: fake.requests.filter(r => r.status === 'APPROVED').map(r => ({ userId: r.userId, startDate: r.startDate, endDate: r.endDate })) };
    }
    if (path === `${T}/leave/requests` && call.method === 'POST') {
      const created = {
        id: `req-${fake.requests.length + 1}`, userId: user, type: call.body.type, startDate: call.body.startDate, endDate: call.body.endDate,
        days: 3, reason: call.body.reason ?? null, status: 'PENDING', autoApproved: false, decisionComment: null, decidedBy: null, createdAt: '2026-11-03T10:00:00Z'
      };
      fake.requests.push(created);
      return { status: 201, body: created };
    }
    if (path === `${T}/leave/requests` && call.method === 'GET') {
      const all = call.search.get('scope') === 'ALL';
      const status = call.search.get('status');
      const items = fake.requests.filter(r => (all || r.userId === user) && (!status || r.status === status));
      return { body: { items, page: 0, size: 20, totalItems: items.length } };
    }
    const decision = path.match(new RegExp(`^${T}/leave/requests/([^/]+)/(approve|reject|cancel)$`));
    if (decision && call.method === 'POST') {
      const found = fake.requests.find(r => r.id === decision[1])!;
      found.status = decision[2] === 'approve' ? 'APPROVED' : decision[2] === 'reject' ? 'REJECTED' : 'CANCELLED';
      found.decisionComment = call.body?.comment ?? null;
      return { body: found };
    }
    if (path === `${T}/leave/policy`) {
      if (call.method === 'PUT') {
        fake.allowance = call.body.allowance;
      }
      return { body: { defaultAllowance: fake.allowance } };
    }
    if (path === `${T}/leave/holidays`) {
      return { body: [] };
    }
    // ---- lunch ----
    if (path === `${T}/lunch/config`) {
      if (call.method === 'PUT') {
        fake.config = call.body;
      }
      return { body: { ...fake.config, timezone: fake.timezone } };
    }
    if (path === `${T}/lunch/rounds/current` && call.method === 'GET') {
      return { body: view(user) };
    }
    if (path === `${T}/lunch/rounds/current/enrollment`) {
      call.method === 'PUT' ? fake.enrolled.add(user) : fake.enrolled.delete(user);
      return { body: view(user) };
    }
    if (path === `${T}/lunch/rounds/current/cancel`) {
      fake.round = { ...fake.round, status: 'CANCELLED', cancelReason: 'BY_APPROVER' };
      return { body: view(user) };
    }
    if (path === `${T}/lunch/rounds` && call.method === 'GET') {
      const items = fake.formed ? [{ ...fake.formed, participantCount: 3, enrolled: fake.formed.members.includes(user) }] : [];
      return { body: { items, page: 0, size: 5, totalItems: items.length } };
    }
    const detail = path.match(new RegExp(`^${T}/lunch/rounds/([^/]+)$`));
    if (detail && fake.formed) {
      const mine = fake.formed.groups.find((g: string[]) => g.includes(user)) ?? [];
      return { body: { round: fake.formed, myGroup: mine, groups: call.authorization && fake.formed.approvers.includes(user) ? fake.formed.groups : [] } };
    }
    return undefined;
  };
}
