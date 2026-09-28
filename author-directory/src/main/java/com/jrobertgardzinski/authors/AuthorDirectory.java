package com.jrobertgardzinski.authors;

import com.jrobertgardzinski.identity.UserId;

import java.util.Collection;
import java.util.Map;

/**
 * The names behind a batch of user ids. An id nobody holds — deleted or invented — is absent from
 * the answer, and the caller shows "deleted account". The port promises nothing stronger than
 * "what identity last said": an adapter may answer from a cache, and a listing must never fail
 * because names could not be fetched.
 */
public interface AuthorDirectory {

    Map<UserId, AuthorName> namesOf(Collection<UserId> ids);
}
