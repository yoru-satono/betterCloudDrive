import { test as base, expect, type APIRequestContext } from '@playwright/test'
import { createUser, type E2EUser } from '../helpers/api'

type E2EFixtures = {
  e2eUser: E2EUser
}

export const test = base.extend<E2EFixtures>({
  e2eUser: async ({ request }, use) => {
    const user = await createUser(request as APIRequestContext)
    await use(user)
  },
})

export { expect }
