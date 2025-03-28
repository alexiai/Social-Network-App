package com.example.socialnetwork;

import socialnetwork.domain.*;
import socialnetwork.domain.validators.FriendRequestValidator;
import socialnetwork.domain.validators.FriendshipValidator;
import socialnetwork.domain.validators.UserValidator;
import socialnetwork.repository.database.db.*;
import socialnetwork.service.Service;
import socialnetwork.ui.Ui;

public class Main {

    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/SocialNetwork";
        String username = "postgres";
        String password = "alexiacojan";

        Repository<Long, User> userRepo = new UserDbRepository(url, username, password, new UserValidator());
        Repository<Tuple<User, User>, Friendship> friendshipRepository = new FriendshipDbRepository(url, username,
                password,new FriendshipValidator());
        Repository<Tuple<User, User>, FriendRequest> friendRequestRepository = new FriendRequestDbRepository(url,
                username, password,new FriendRequestValidator());
        Repository<Long, Message> messageRepo = new MessageDbRepository(url, username, password,userRepo);
        EventDbRepository eventRepo = new EventDbRepository(url, username, password, userRepo);

        Service service = new Service(userRepo,friendshipRepository,messageRepo, friendRequestRepository, eventRepo);

        Ui ui = new Ui(service);
        ui.run();
    }

}

