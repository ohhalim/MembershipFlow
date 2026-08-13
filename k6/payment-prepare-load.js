import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081').replace(/\/$/, '');
const PLAN_ID = Number(__ENV.PLAN_ID || 1);
const PROFILE = __ENV.PROFILE || 'smoke';
const TOKENS_FILE = __ENV.TOKENS_FILE || './payment-test-users.json';
const RUN_ID = __ENV.RUN_ID || `payment-prepare-${Date.now()}`;
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0.2);

const testUsers = JSON.parse(open(TOKENS_FILE));

const prepareFailures = new Rate('payment_prepare_failures');
const contractFailures = new Counter('payment_prepare_contract_failures');
const prepareDuration = new Trend('payment_prepare_duration', true);

const profiles = {
  smoke: {
    executor: 'per-vu-iterations',
    vus: 1,
    iterations: 3,
    maxDuration: '30s',
  },
  contention: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '10s', target: 5 },
      { duration: '30s', target: 10 },
      { duration: '30s', target: 10 },
      { duration: '10s', target: 0 },
    ],
    gracefulRampDown: '10s',
  },
  load: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 5 },
      { duration: '1m', target: 20 },
      { duration: '2m', target: 20 },
      { duration: '30s', target: 0 },
    ],
    gracefulRampDown: '15s',
  },
};

if (!profiles[PROFILE]) {
  throw new Error(`Unsupported PROFILE=${PROFILE}. Use smoke, contention, or load.`);
}

export const options = {
  scenarios: {
    payment_prepare: profiles[PROFILE],
  },
  thresholds: {
    checks: ['rate>0.99'],
    payment_prepare_failures: ['rate<0.01'],
    payment_prepare_contract_failures: ['count==0'],
    'http_req_duration{endpoint:payment_prepare}': ['p(95)<500', 'p(99)<1000'],
  },
  tags: {
    test_type: 'payment_prepare',
    profile: PROFILE,
    run_id: RUN_ID,
  },
};

function isProductionTarget() {
  return /(^|\/\/)(www\.)?membershipflow\.site(?::\d+)?(\/|$)/i.test(BASE_URL)
    || BASE_URL.includes('54.116.105.113');
}

function validateConfiguration() {
  if (!Number.isInteger(PLAN_ID) || PLAN_ID < 1) {
    fail('PLAN_ID must be a positive integer.');
  }
  if (!Array.isArray(testUsers) || testUsers.length === 0) {
    fail('TOKENS_FILE must contain at least one dedicated test user.');
  }
  for (const [index, user] of testUsers.entries()) {
    if (!user.name || !user.accessToken) {
      fail(`TOKENS_FILE entry ${index} requires name and accessToken.`);
    }
    if (user.accessToken.includes('replace-with')) {
      fail('Replace the example access token before running the test.');
    }
  }
  if (isProductionTarget()) {
    if (__ENV.ALLOW_PRODUCTION_PAYMENT_PREPARE !== 'writes-data-but-never-calls-toss') {
      fail('Production is blocked. Set the explicit production acknowledgement only after approval.');
    }
    if (PROFILE !== 'smoke') {
      fail('Only PROFILE=smoke is allowed against production. Use local or staging for load tests.');
    }
  }
}

function authHeaders(accessToken) {
  return {
    Authorization: `Bearer ${accessToken}`,
    Accept: 'application/json',
    'X-Load-Test-Run-Id': RUN_ID,
  };
}

function parsePrepareResponse(response, userName) {
  if (response.status !== 200) {
    fail(`Prepare preflight failed for ${userName}: HTTP ${response.status}`);
  }
  let body;
  try {
    body = response.json();
  } catch (_error) {
    fail(`Prepare preflight returned non-JSON for ${userName}.`);
  }
  if (!body.customerKey || body.planId !== PLAN_ID) {
    fail(`Prepare preflight contract mismatch for ${userName}.`);
  }
  return body.customerKey;
}

export function setup() {
  validateConfiguration();

  const plans = http.get(`${BASE_URL}/api/v1/subscriptions/plans`, {
    tags: { endpoint: 'subscription_plans', phase: 'preflight' },
  });
  if (plans.status !== 200) {
    fail(`Plan preflight failed: HTTP ${plans.status}`);
  }

  const baselines = testUsers.map((user) => {
    const response = http.post(
      `${BASE_URL}/api/v1/subscriptions/prepare?planId=${PLAN_ID}`,
      null,
      {
        headers: authHeaders(user.accessToken),
        tags: { endpoint: 'payment_prepare', phase: 'preflight' },
      },
    );
    return {
      name: user.name,
      accessToken: user.accessToken,
      customerKey: parsePrepareResponse(response, user.name),
    };
  });

  return { baselines };
}

export default function (data) {
  const index = (exec.vu.idInTest - 1) % data.baselines.length;
  const user = data.baselines[index];
  const response = http.post(
    `${BASE_URL}/api/v1/subscriptions/prepare?planId=${PLAN_ID}`,
    null,
    {
      headers: authHeaders(user.accessToken),
      tags: { endpoint: 'payment_prepare', phase: 'load' },
      timeout: '5s',
    },
  );

  prepareDuration.add(response.timings.duration);

  let body = null;
  if (response.status === 200) {
    try {
      body = response.json();
    } catch (_error) {
      contractFailures.add(1);
    }
  }

  const passed = check(response, {
    'prepare returns 200': (res) => res.status === 200,
    'same active billing attempt is reused': () => body?.customerKey === user.customerKey,
    'response keeps requested plan': () => body?.planId === PLAN_ID,
    'response does not expose a billing key': () => body
      && !Object.prototype.hasOwnProperty.call(body, 'billingKey'),
  });

  prepareFailures.add(!passed);
  sleep(THINK_TIME_SECONDS);
}
