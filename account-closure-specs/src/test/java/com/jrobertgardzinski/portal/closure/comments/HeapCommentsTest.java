package com.jrobertgardzinski.portal.closure.comments;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.comments.application.CommentErasureContractTest;

/** The heap the portal's specs run the comment threads on, held to what the real adapter promises. */
class HeapCommentsTest extends CommentErasureContractTest {

    private final HeapComments comments = new HeapComments();

    @Override
    protected CommentErasure erasure() {
        return comments;
    }

    @Override
    protected void givenActiveComment(String id, String author) {
        comments.wrote(id, author);
    }
}
